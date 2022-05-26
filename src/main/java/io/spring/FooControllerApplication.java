package io.spring;

import com.google.gson.JsonElement;
import io.kubernetes.client.extended.controller.Controller;
import io.kubernetes.client.extended.controller.builder.ControllerBuilder;
import io.kubernetes.client.extended.controller.reconciler.Reconciler;
import io.kubernetes.client.extended.controller.reconciler.Result;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1DeploymentList;
import io.kubernetes.client.openapi.models.V1OwnerReference;
import io.kubernetes.client.util.Yaml;
import io.kubernetes.client.util.generic.GenericKubernetesApi;
import io.spring.models.V1Foo;
import io.spring.models.V1FooList;
import io.spring.models.V1FooSpec;
import io.spring.models.V1FooStatus;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.nativex.hint.TypeAccess;
import org.springframework.nativex.hint.TypeHint;
import org.springframework.util.FileCopyUtils;

import java.io.InputStreamReader;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

//
// https://github.com/kubernetes-client/java/tree/master/examples/examples-release-13
//
@TypeHint(access = {TypeAccess.DECLARED_FIELDS, TypeAccess.DECLARED_METHODS, TypeAccess.DECLARED_CONSTRUCTORS, TypeAccess.DECLARED_CLASSES}, types = {JsonElement.class, V1Foo.class, V1FooList.class, V1FooSpec.class, V1FooStatus.class})
@Log4j2
@SpringBootApplication
public class FooControllerApplication {

    public static void main(String[] args) {
        SpringApplication.run(FooControllerApplication.class, args);
    }

    @Bean
    GenericKubernetesApi<V1Deployment, V1DeploymentList> deploymentsApi(ApiClient apiClient) {
        return new GenericKubernetesApi<>(V1Deployment.class, V1DeploymentList.class, "", "v1", "deployments", apiClient);
    }

    @Bean
    GenericKubernetesApi<V1Foo, V1FooList> foosApi(ApiClient apiClient) {
        return new GenericKubernetesApi<>(V1Foo.class, V1FooList.class, "spring.io", "v1", "foos", apiClient);
    }

    @Bean
    SharedIndexInformer<V1Foo> fooNodeInformer(SharedInformerFactory sharedInformerFactory, GenericKubernetesApi<V1Foo, V1FooList> configClientApi) {
        return sharedInformerFactory.sharedIndexInformerFor(configClientApi, V1Foo.class, 0);
    }

    @Bean
    AppsV1Api appsV1Api(ApiClient apiClient) {
        return new AppsV1Api(apiClient);
    }

/*
    @Bean
    Reconciler reconciler(AppsV1Api coreV1Api,
                          @Value("classpath:/deployment.yaml") Resource resourceForDeploymentYaml,
                          SharedIndexInformer<V1Foo> fooNodeInformer,
                          GenericKubernetesApi<V1Deployment, V1DeploymentList> deploymentApi) {
        return new FooReconciler(coreV1Api, resourceForDeploymentYaml, fooNodeInformer, deploymentApi);
    }
*/

    @Bean
    Controller controller(SharedInformerFactory sharedInformerFactory,
                          SharedIndexInformer<V1Foo> fooNodeInformer,
                          Reconciler reconciler) {
        var builder = ControllerBuilder //
                .defaultBuilder(sharedInformerFactory)//
                .watch((queue) -> ControllerBuilder //
                        .controllerWatchBuilder(V1Foo.class, queue)//
                        .withResyncPeriod(Duration.ofSeconds(1))//
                        .build() //
                ) //
                .withWorkerCount(2);
        return builder.withReconciler(reconciler) //
                .withReadyFunc(fooNodeInformer::hasSynced) // optional: only start once the index is synced
                .withName("fooController") ///
                .build();

    }

    @Bean
    ExecutorService executorService() {
        return Executors.newCachedThreadPool();
    }

    @Bean
    ApplicationRunner runner(ExecutorService executorService, SharedInformerFactory sharedInformerFactory, Controller controller) {
        return args -> executorService.execute(() -> {
            sharedInformerFactory.startAllRegisteredInformers();
            controller.run();
        });
    }

    /**
     * the Reconciler won't get an event telling it that the cluster has changed,
     * but instead it looks at cluster state and determines that something has changed
     */
    @Bean
    Reconciler reconciler(
            @Value("classpath:deployment.yaml") Resource deploymentYaml,
            GenericKubernetesApi<V1Deployment, V1DeploymentList> deploymentApi,
            SharedIndexInformer<V1Foo> v1FooSharedIndexInformer,
            GenericKubernetesApi<V1Foo, V1FooList> fooApi,
            AppsV1Api appsV1Api) {
        return request -> {
            try {


                // create new one on k apply -f foo.yaml
                String key = request.getNamespace() + '/' + request.getName();
                V1Foo foo = v1FooSharedIndexInformer.getIndexer().getByKey(key);
                V1Deployment deployment = loadYamlAs(deploymentYaml, V1Deployment.class);
                deployment.getMetadata().setName("deployment-" + request.getName());
                deployment.getMetadata()
                        .addOwnerReferencesItem(
                                new V1OwnerReference()
                                        .kind(foo.getKind())
                                        .apiVersion(foo.getApiVersion())
                                        .controller(true)
                                        .uid(foo.getMetadata().getUid())
                                        .name(request.getName())
                        );

                String namespace = foo.getMetadata().getNamespace();
                String pretty = "true";
                String dryRun = null;
                String fieldManager = "";
                String fieldValidation = "";

                try {

                    appsV1Api.createNamespacedDeployment(
                            namespace, deployment, pretty, dryRun, fieldManager, fieldValidation);
                    System.out.println("It worked! we created a new one!");
                }//
                catch (Throwable throwable) {

                    if (throwable instanceof ApiException apiException) {
                        log.info("the Deployment already exists. Replacing.");
                        int code = apiException.getCode();
                        if (code == 409) { // already exists
                            appsV1Api.replaceNamespacedDeployment(deployment.getMetadata().getName(),
                                    namespace, deployment, pretty, dryRun, fieldManager, fieldValidation);

                        }
                    }
                    else {
                        log.error("we've got an error.", throwable);
                    }
                }


            }//
            catch (Throwable e) {
                log.error("we've got an outer error.", e);
            }

            return new Result(false);

        };
    }

    @SneakyThrows
    private static <T> T loadYamlAs(Resource resource, Class<T> clzz) {
        var yaml = FileCopyUtils.copyToString(
                new InputStreamReader(resource.getInputStream()));
        return Yaml.loadAs(yaml, clzz);
    }


}


/*

@Slf4j
record FooReconciler(AppsV1Api coreV1Api, Resource resourceForDeploymentYaml,
                     SharedIndexInformer<V1Foo> fooNodeInformer,
                     GenericKubernetesApi<V1Deployment, V1DeploymentList> deploymentApi) implements Reconciler {

    @Override
    @SneakyThrows
    public Result reconcile(Request request) {


    var foo = fooNodeInformer.getIndexer().getByKey(request.getNamespace() + "/" + request.getName());
        if (foo != null) {
            log.info("there's a new Foo in town! Let's make sure we've got a deployment to match...");
            var nameOfDeployment = foo.getMetadata().getName() + "-deployment";
            log.info("does the deployment called " + nameOfDeployment + " exist?");

            var existingDeployment = this.deploymentApi
                    .get(foo.getMetadata().getNamespace(), nameOfDeployment);
            if (existingDeployment.isSuccess()) {
                log.info("the deployment already exists!");
            } //
            else {
                var deployment = loadYamlAs(resourceForDeploymentYaml, V1Deployment.class);
                deployment.getMetadata().setName(nameOfDeployment);
                try {
                    var namespacedDeployment = this.coreV1Api.createNamespacedDeployment(
                            foo.getMetadata().getNamespace(), deployment, "true", null, "", "");
                    Assert.notNull(namespacedDeployment, () -> "the Deployment result should be non-null");
                    log.info("created a deployment called " + nameOfDeployment);
                } //
                catch (Throwable t) {
                    log.error("nooo!", t);
                }
            }
        }//
        else {
            log.info("there are no Foos that match the requested name and namespace. " +
                    "We should check for the deployment and delete it if it exists");

        }

        return new Result(false);
    }

    @SneakyThrows
    private static <T> T loadYamlAs(Resource resource, Class<T> clzz) {
        var yaml = FileCopyUtils.copyToString(
                new InputStreamReader(resource.getInputStream()));
        return Yaml.loadAs(yaml, clzz);
    }
}
*/

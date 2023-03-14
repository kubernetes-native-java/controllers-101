package io.spring;

import io.spring.models.V1Foo;
import io.spring.models.V1FooList;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.extended.controller.Controller;
import io.kubernetes.client.extended.controller.builder.ControllerBuilder;
import io.kubernetes.client.extended.controller.builder.DefaultControllerBuilder;
import io.kubernetes.client.extended.controller.reconciler.Reconciler;
import io.kubernetes.client.extended.controller.reconciler.Result;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.Yaml;
import io.kubernetes.client.util.generic.GenericKubernetesApi;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.FileCopyUtils;

import java.io.InputStreamReader;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;


@Slf4j
@ImportRuntimeHints(ControllersApplication.FooControllerRuntimeHints.class)
@SpringBootApplication
public class ControllersApplication {

	public static void main(String[] args) {
		SpringApplication.run(ControllersApplication.class, args);
	}

	static class FooControllerRuntimeHints implements RuntimeHintsRegistrar {

		@Override
		public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
			for (var path : new String[] { "/configmap.yaml", "/deployment.yaml" }) {
				hints.resources().registerResource(new ClassPathResource(path));
			}
		}

	}

	@Bean
	GenericKubernetesApi<V1Foo, V1FooList> foosApi(ApiClient apiClient) {
		return new GenericKubernetesApi<>(V1Foo.class, V1FooList.class, "spring.io", "v1", "foos", apiClient);
	}

	@Bean
	GenericKubernetesApi<V1Deployment, V1DeploymentList> deploymentsApi(ApiClient apiClient) {
		return new GenericKubernetesApi<>(V1Deployment.class, V1DeploymentList.class, "", "v1", "deployments",
				apiClient);
	}

	@Bean
	SharedIndexInformer<V1Foo> foosSharedIndexInformer(SharedInformerFactory sharedInformerFactory,
													   GenericKubernetesApi<V1Foo, V1FooList> api) {
		return sharedInformerFactory.sharedIndexInformerFor(api, V1Foo.class, 0);
	}

	@Bean
	AppsV1Api appsV1Api(ApiClient apiClient) {
		return new AppsV1Api(apiClient);
	}

	@Bean
	CoreV1Api coreV1Api(ApiClient apiClient) {
		return new CoreV1Api(apiClient);
	}

	@Bean(destroyMethod = "shutdown")
	Controller fooController(SharedInformerFactory sharedInformerFactory, SharedIndexInformer<V1Foo> fooNodeInformer,
							 Reconciler reconciler) {

		DefaultControllerBuilder builder = ControllerBuilder //
				.defaultBuilder(sharedInformerFactory)//
				.watch(fooQ -> ControllerBuilder //
						.controllerWatchBuilder(V1Foo.class, fooQ)//
						.withResyncPeriod(Duration.ofSeconds(1))//
						.build()) //
				.withWorkerCount(2);
		return builder//
				.withReconciler(reconciler) //
				.withReadyFunc(fooNodeInformer::hasSynced) // optional: only start once
				// the index is synced
				.withName("fooController") ///
				.build();

	}

	@Bean
	ApplicationRunner runner(SharedInformerFactory sharedInformerFactory, Controller controller) {
		var executorService = Executors.newCachedThreadPool();
		return args -> executorService.execute(() -> {
			sharedInformerFactory.startAllRegisteredInformers();
			controller.run();
		});
	}

	@FunctionalInterface
	interface ApiSupplier<T> {

		T get() throws ApiException;

	}

	/**
	 * the Reconciler won't get an event telling it that the cluster has changed, but
	 * instead it looks at cluster state and determines that something has changed
	 */
	@Bean
	Reconciler reconciler(@Value("classpath:configmap.yaml") Resource configMapYaml,
						  @Value("classpath:deployment.yaml") Resource deploymentYaml,
						  SharedIndexInformer<V1Foo> v1FooSharedIndexInformer, AppsV1Api appsV1Api, CoreV1Api coreV1Api) {
		return request -> {
			try {
				// create new one on k apply -f foo.yaml
				String requestName = request.getName();
				String key = request.getNamespace() + '/' + requestName;
				V1Foo foo = v1FooSharedIndexInformer.getIndexer().getByKey(key);
				if (foo == null) { // deleted. we use ownerreferences so dont need to do
					// anything special here
					return new Result(false);
				}

				String namespace = foo.getMetadata().getNamespace();
				String pretty = "true";
				String dryRun = null;
				String fieldManager = "";
				String fieldValidation = "";

				// parameterize configmap
				String configMapName = "configmap-" + requestName;
				V1ConfigMap configMap = loadYamlAs(configMapYaml, V1ConfigMap.class);
				String html = "<h1> Hello, " + foo.getSpec().getName() + " </h1>";
				configMap.getData().put("index.html", html);
				configMap.getMetadata().setName(configMapName);
				String deploymentName = "deployment-" + requestName;
				createOrUpdate(V1ConfigMap.class, () -> {
					addOwnerReference(requestName, foo, configMap);
					return coreV1Api.createNamespacedConfigMap(namespace, configMap, pretty, dryRun, fieldManager,
							fieldValidation);
				}, () -> {
					V1ConfigMap v1ConfigMap = coreV1Api.replaceNamespacedConfigMap(configMapName, namespace, configMap,
							pretty, dryRun, fieldManager, fieldValidation);
					// todo now we need to add an annotation to the deployment

					return v1ConfigMap;
				});

				// parameterize deployment
				V1Deployment deployment = loadYamlAs(deploymentYaml, V1Deployment.class);
				deployment.getMetadata().setName(deploymentName);
				List<V1Volume> volumes = deployment.getSpec().getTemplate().getSpec().getVolumes();
				Assert.isTrue(volumes.size() == 1, () -> "there should be only one V1Volume");
				volumes.forEach(vol -> vol.getConfigMap().setName(configMapName));
				createOrUpdate(V1Deployment.class, () -> {
					deployment.getSpec().getTemplate().getMetadata()
							.setAnnotations(Map.of("bootiful-update", Instant.now().toString()));
					addOwnerReference(requestName, foo, deployment);
					return appsV1Api.createNamespacedDeployment(namespace, deployment, pretty, dryRun, fieldManager,
							fieldValidation);
				}, () -> {
					updateAnnotation(deployment);
					return appsV1Api.replaceNamespacedDeployment(deploymentName, namespace, deployment, pretty, dryRun,
							fieldManager, fieldValidation);
				});
			} //
			catch (Throwable e) {
				log.error("we've got an outer error.", e);
				return new Result(true, Duration.ofSeconds(60));
			}
			return new Result(false);
		};
	}

	private void updateAnnotation(V1Deployment deployment) {
		Objects.requireNonNull(Objects.requireNonNull(deployment.getSpec()).getTemplate().getMetadata())
				.setAnnotations(Map.of("bootiful-update", Instant.now().toString()));
	}

	static private <T> void createOrUpdate(Class<T> clazz, ApiSupplier<T> creator, ApiSupplier<T> updater) {
		try {
			creator.get();
			log.info("It worked! we created a new " + clazz.getName() + "!");
		} //
		catch (ApiException throwable) {
			int code = throwable.getCode();
			if (code == 409) { // already exists
				log.info("the " + clazz.getName() + " already exists. Replacing.");
				try {
					updater.get();
					log.info("successfully updated the " + clazz.getName());
				}
				catch (ApiException ex) {
					log.error("got an error on update", ex);
				}
			} //
			else {
				log.info("got an exception with code " + code + " while trying to create the " + clazz.getName());
			}
		}
	}

	private static V1ObjectMeta addOwnerReference(String requestName, V1Foo foo, KubernetesObject kubernetesObject) {
		Assert.notNull(foo, () -> "the V1Foo must not be null");
		return kubernetesObject.getMetadata().addOwnerReferencesItem(new V1OwnerReference().kind(foo.getKind())
				.apiVersion(foo.getApiVersion()).controller(true).uid(foo.getMetadata().getUid()).name(requestName));
	}

	@SneakyThrows
	private static <T> T loadYamlAs(Resource resource, Class<T> clzz) {
		var yaml = FileCopyUtils.copyToString(new InputStreamReader(resource.getInputStream()));
		return Yaml.loadAs(yaml, clzz);
	}

}
package io.spring;

import com.dajudge.kindcontainer.exception.ExecutionException;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.util.generic.GenericKubernetesApi;
import io.spring.models.V1Foo;
import io.spring.models.V1FooList;
import io.spring.models.V1FooSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static io.spring.TestApiClientFactory.API_SERVER;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

@SpringBootTest(classes = {TestApiClientFactory.class})
class FooControllerApplicationTest {
    @Autowired
    ApiClient client;
    @Autowired
    GenericKubernetesApi<V1Foo, V1FooList> fooClient;
    @Autowired
    AppsV1Api appsApi;

    String namespace = UUID.randomUUID().toString();

    @BeforeEach
    public void setup() throws IOException, ExecutionException, InterruptedException {
        API_SERVER.kubectl().create.namespace.run(namespace);
    }

    @Test
    public void testReconciler() {
        fooClient.create(new V1Foo()
                .kind("Foo")
                .apiVersion("spring.io/v1")
                .metadata(new V1ObjectMeta()
                        .name("my-foo")
                        .namespace(namespace))
                .spec(new V1FooSpec()
                        .name("oh-my")));

        await("deployment")
                .timeout(10, SECONDS)
                .until(() -> !getDeployments().isEmpty());
    }

    private List<V1Deployment> getDeployments() throws ApiException {
        return appsApi.listNamespacedDeployment(namespace, null, null, null, null, null, null, null, null, null, null).getItems();
    }
}
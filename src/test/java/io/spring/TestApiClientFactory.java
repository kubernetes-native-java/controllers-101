package io.spring;

import com.dajudge.kindcontainer.ApiServerContainer;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import org.junit.ClassRule;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.utility.MountableFile;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;

@TestConfiguration
public class TestApiClientFactory {
    // TODO this would be unnecessary if the CRD was on the classpath
    private static final File CRD_PATH = getCrdPath();

    public static final ApiServerContainer<?> API_SERVER = new ApiServerContainer<>()
            .withKubectl(kubectl -> {
                // TODO this could be prettier if the CRD was on the classpath
                kubectl.copyFileToContainer(MountableFile.forHostPath(new File(CRD_PATH, "foo.yaml").getAbsolutePath()), "/tmp/foo.yaml");
                kubectl.apply.from("/tmp/foo.yaml").run();
                kubectl.wait.forCondition("Established").run("crd", "foos.spring.io");
            });

    @Bean
    public ApiClient getTestApiClient() throws IOException {
        API_SERVER.start();
        return ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(new StringReader(API_SERVER.getKubeconfig()))).build();
    }

    private static File getCrdPath() {
        final ClassLoader classLoader = FooControllerApplicationTest.class.getClassLoader();
        final File file = new File(classLoader.getResource("application.properties").getFile());
        final File projectDir = file.getParentFile().getParentFile().getParentFile();
        return new File(projectDir, "k8s/crds");
    }
}

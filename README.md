# You're Going to Get Through This



## Generate a new spring native project from start.spring.io

We'll need a new project from the [Spring Initializr](https://start.spring.io). Make sure to select `GraalVM Native Image` and `Lombok`.

It'll generate everything and put it in the `io.spring.controllers` package. To keep things simpler, we've moved everything up one package, to `io.spring`. Delete the `controllers` package for both `src/main` and `src/test`. 

## Customize the build file 

The Spring Initializr will get us most of the way (it does _a lot _ of code generation) but we need to add an extra dependency -- the official Java client for Kubernetes.

If you're using Apache Maven, add this:

```xml
        <dependency>
            <groupId>io.kubernetes</groupId>
            <artifactId>client-java-spring-aot-integration</artifactId>
            <version>17.0.0</version>
        </dependency>
```

If you're using Gradle, add this to your dependencies:

```groovy
    implementation 'io.kubernetes:client-java-spring-aot-integration:17.0.0'
```

## Stage

Copy `bin` and `k8s` from the source code to the new project generated from the Spring Initializr



## Deploy the CRD to Kubernetes

```shell
k apply -f bin/foo.yaml
```

We should be able to do


```shell
k get crds
```

And see the newly minted CRD. Now would also be an apropos time to show the audience the soul-annihilating-ly tedious definition of the CRD itself (`foo.yaml`). This CRD is why the Kubernetes community can't have nice things.

We should also show the `test.yaml`, but don't apply it yet. This way people get the distinction between the archetypal definition of the CRD and an instance of the CRD.

## Run the Code Generator for the Image

We'll need a little script to help us code-generate the Java code for our CRDs. Copy the `regen_crds.sh` script from our backup directory to the new project, in a directory called `bin`:

```shell 
./bin/regen_crd.sh
```

## And then a Miracle Happens

There are several main concepts we need to understand before the code we're about to write makes sense.


### Controller

Kubernetes is an edge-leveled reconciling controller. Basically, it will spin up a loop that evaluates some system state and if that system state should ever drift from the operator's desired state, the controller's job is to make that state so. So, llogically, a K8s CRD is two things: a CRD definition and a controller that reacts to the lifecycle of new instances of that CRD. We've already defined the CRD itself and looked at the generated code for the CRD instance itself. We're halfway there! We just need the controller itself. That'll be our first Spring `@Bean`.

```java

    @Bean
    Controller controller(SharedInformerFactory sharedInformerFactory,
                          SharedIndexInformer<V1Foo> fooNodeInformer,
                          Reconciler reconciler) {
        var builder = ControllerBuilder //
                .defaultBuilder(sharedInformerFactory)//
                .watch((q) -> ControllerBuilder //
                        .controllerWatchBuilder(V1Foo.class, q)
                        .withResyncPeriod(Duration.ofHours(1)).build() //
                ) //
                .withWorkerCount(2);
        return builder
                .withReconciler(reconciler) //
                .withReadyFunc(fooNodeInformer::hasSynced) // optional: only start once the index is synced
                .withName("fooController") ///
                .build();

    }
```

Things are broken! We don't have any of the three dependencies expressed here: `SharedInformerFactory`, `Reconciler`, and `SharedIndexInformer<V1Foo>`.

```java

    @Bean
    SharedIndexInformer<V1Foo> fooNodeInformer(
            SharedInformerFactory sharedInformerFactory,
            GenericKubernetesApi<V1Foo, V1FooList> api) {
        return sharedInformerFactory.sharedIndexInformerFor(api, V1Foo.class, 0);
    }

```

This in turn implies a dependency on `GenericKubernetesApi<V1Foo,V1FooList>`.

```java 
 @Bean
    GenericKubernetesApi<V1Foo, V1FooList> foosApi(ApiClient apiClient) {
        return new GenericKubernetesApi<>(V1Foo.class, V1FooList.class, "spring.io", "v1",
                "foos", apiClient);
    }
```

We'll also need a `GenericKubernetesApi` for `Deployment`s, too, so let's get that out of the way now:


```java 
   @Bean
    GenericKubernetesApi<V1Deployment, V1DeploymentList> deploymentsApi(ApiClient apiClient) {
        return new GenericKubernetesApi<>(V1Deployment.class, V1DeploymentList.class,
                "", "v1", "deployments",
                apiClient);
    }

```

They're identical, except for their generic parameters and the string definitions of the group, and pluralized form of their nouns. These API clients let us talk to the API server about a given type of CRD, in this case `Foo` and `Deployment`, respectively.

We need the `SharedIndexInformer<V1Foo>`, too.

What's an `Informer`, you ask? An informer "is a" - and we're not making this iup - controller together with the ability to distribute its `Queue`-related operations to an appropriate event handler.  There are `SharedInformer`s that share data across multiple instances of the `Informer` so that they're not duplicated. A `SharedInformer` has a shared data cache and is capable of distributing notifications for changes to the cache to multiple listeners who registered with it. There is one behavior change compared to a standard `Informer`: when you receive a notification, the cache will be _at least_ as fresh as the notification, but it _may_ be more fresh. You should not depend on the contents of the cache exactly matching the state implied by the notification. The notification is binding. `SharedIndexInformer` only adds one more thing to the picture: the ability to lookup items by various keys. So, a controller sometimes needs a conceptually-a-controller to be a controller. Got it? Got it.

Next, we'll need to define the `Reconciler` itself:

```java
   @Bean
    Reconciler reconciler(
            @Value("classpath:/deployment.yaml") Resource resourceForDeploymentYaml,
            AppsV1Api coreV1Api,
            SharedIndexInformer<V1Foo> fooNodeInformer,
        GenericKubernetesApi<V1Deployment, V1DeploymentList> deploymentApi) {
        return new FooReconciler(coreV1Api, resourceForDeploymentYaml, fooNodeInformer, deploymentApi);
    }
```

Here's where the rubber meets the road: our reconciler will create a new `Deployment` every time a new `Foo` is created. We like you too much to programmatically build up the `Deployment` from scratch in Java, so we'll just reuse a pre-written YAML definition (`/deployment.yaml`) of a `Deployment` and then reify it, changing some of its parameters, and submit that.

We'll also need references fo the `GenericKubernetesAPi` for `Deployments` and a new thing, called the `AppsV1Api`. This API sidesteps all the caching and indexing and allows us to talk directly to the API server. You could achieve this without using the API, but it simplifies things sometimes and it's instructional to see it in action, so:

```java
    @Bean
    AppsV1Api appsV1Api(ApiClient apiClient) {
       return new AppsV1Api(apiClient);
    }
```

## Deploy an Instance of the `foo` Object

```shell
k apply -f bin/test.yaml
```

## Run the Program

If you're using Apache Maven: `./mvnw spring-boot:run`

If you're using Gradle: `./gradlew bootRun`


## Compile a GraalVM Native Image


If you're using Apache Maven: `./mvnw -Pnative native:compile`

If you're using Gradle: `./gradlew nativeCompile`


## Resources
- [we found the following post supremely useful for navigating this nightmare world](https://lairdnelson.wordpress.com/2018/01/07/understanding-kubernetes-tools-cache-package-part-3/)
- [Generating models from CRD YAML definitions for fun and profit](https://github.com/kubernetes-client/java/blob/master/docs/generate-model-from-third-party-resources.md)


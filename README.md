# You're Going to Get Through This



## Generate a new spring native project from start.spring.io

We'll need a new project from the [Spring Initializr](https://start.spring.io). Make sure to select `Spring Native` and `Reactive Web`, and `Lombok`.

## Want to use Native and AOT? 

Make sure that the code in https://github.com/kubernetes-client/java/pull/2402 gets contributed or compile it yourself :) 

## Add to `pom.xml`

The Spring Initializr will get us most of the way (it does _a lot _ of code generation) but we need to add some extra dependencies. The first dependency
is the official Java client for Kubernetes. The second dependency is a set of custom Spring Native hints that Josh wrote for a bunch of different projects. In an ideal world, this won't be required forever. When Spring Framework 6 drops, this kind of stuff might be better placed in the official Java client itself. 
If you want to use the RedHat Fabric8 Java client, well there are hints for that, too! And conceptually, a lot of what we're going to look at is the same in both Fabric 8 and the official Java client, since they're both code-generated from the same OpenAPI definitions. The packages and particulars may change, of course. 

Anyway, add:

```xml
        <dependency>
            <groupId>io.kubernetes</groupId>
            <artifactId>client-java-spring-integration</artifactId>
            <version>16.0.0</version>
            <optional>true</optional>
        </dependency>
  
```

## Stage

Copy `bin` and `k8s` from the source code to the new project generated from the Spring Initializr

## Authenticate with GitHub Because Reasons

You'll need a GitHub Personal Access Token (PAT). You can get the PAT from GitHub Settings 

```shell
cat ~/TOKEN.txt | docker login https://docker.pkg.github.com -u USERNAME --password-stdin 
```

## Deploy the CRD to Kubernetes 

```shell
k apply -f k8s/crds/foo.yaml
```

We should be able to do 


```shell
k get crds
```

And see the newly minted CRD. Now would also be an apropos time to show the audience the soul-annihilatingly tedious definition of the CRD itself (`foo.yaml`). This CRD is why the K8s community can't have nice things.

We should also show the `test.yaml`, but don't apply it yet. This way people get the distinction between the archetypal definition of the CRD and an instance of the CRD. 

## Run the Code Generator for the Image

We'll need a little script to help us code-generate the Java code for our CRDs. Copy the `regen_crd_java.sh` script from our backup directory to the new project, in a directory called `bin`:

```shell 
./bin/regen_crd_java.sh
```

You should have some source code dumped in a directory specified in `gen` to be moved to the `src/main/java/io/spring/models` folder. Introduce the code generated Java classes. 

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
k apply -f k8s/crds/test.yml
```




## Resources 
- [we found the following post supremely useful for navigating this nightmare world](https://lairdnelson.wordpress.com/2018/01/07/understanding-kubernetes-tools-cache-package-part-3/)
- [Generating models from CRD YAML definitions for fun and profit](https://github.com/kubernetes-client/java/blob/master/docs/generate-model-from-third-party-resources.md)


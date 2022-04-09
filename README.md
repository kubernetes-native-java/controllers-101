# You're Going to Get Through This


## Authenticate with Github Because Reasons 
    
```shell
cat ~/TOKEN.txt | docker login https://docker.pkg.github.com -u USERNAME --password-stdin 
```

## Run the Code Generator for the Image 

```shell 
./bin/regen_crd_java.sh
```


## Resources 
- [Generating models from CRD YAML definitions for fun and profit](https://github.com/kubernetes-client/java/blob/master/docs/generate-model-from-third-party-resources.md)


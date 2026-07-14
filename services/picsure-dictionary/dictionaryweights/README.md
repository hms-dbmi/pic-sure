## Docker commands for local development
### Docker build
```bash
docker build --no-cache --build-arg SPRING_PROFILE=bdc-dev -t weights:latest .
```

### Docker run
You will need a local weights.csv file.
```bash
    docker run --rm -t --name dictionary-weights --network=host -v ./weights.csv:/weights.csv weights:latest
```
# Proposed end to end build process

Issues with current end to end build process:
- Lots of bash / ansible scripts (not a purpose build tool)
- Lengthy process
- Team understanding of the process
- Deployed image is not the same as the image tested due to scripted updates of version etc

## Pre-setup repositories
- https://github.com/dan05011991/virtualbox_jenkins_home - This has a backup of the jenkins instance used for testing
- https://github.com/dan05011991/jenkins-libraries - jenkins pipelines

## Installation

Make the jenkins directory on the box where jenkins will be deployed

```bash
mkdir -p /var/jenkins_home
```

Install docker and docker-compose

```bash
yum install -y docker docker-compose
```

Save the following as `docker-compose.yaml`. In the same directory, run `docker-compose up -d`

```yaml
version: "3.7"
services:
  jenkins:
    image: jenkins/jenkins
    privileged: true
    ports:
      - "8080:8080"
      - "50000:50000"
    volumes:
      - /var/jenkins_home:/var/jenkins_home:z
      - /var/run/docker.sock:/var/run/docker.sock
    networks:
      - jenkins
volumes:
  db-data:
networks:
  jenkins:
```

## Setup

### Plugins to install 

To install plugins go to Manage Jenkins -> Manage Plugins -> Available
- Kubernetes
- Credentials
- Pipelines
- Bitbucket
- Git
- Maven
- Docker

### Create multi-branch pipeline jobs
Create multi-branch pipeline jobs for each project you wish to build. You will also need to add a jenkins file into each project repository also. 

Example jenkins file
```jenkins file
builder(
    buildType: 'maven',
    projectKey: 'backend'
    test: 'test.dockerfile'
) 
```

- buildType = used to instruct pipeline which steps are applicable
- projectKey = used to request a release number for releases
- test = docker file used to test build

## Gitflow
Designed to follow the standard gitflow workflow for development branching and for versioninig. 
![Gitflow example](https://dzone.com/storage/temp/12887668-1577951038067.png)

### Main branches:
- Master: representative of operational system
- Develop: integration branch 

Example: 
- Feature branches: feature/example-1 (branching off develop - finishing by merging into develop)
- Release branches: release/release-1 (branching off develop - finishing by merging into develop and master)
- Hotfix branches: hotfix/hotfix-1 (branching off master - finishing by merging into master)

## Build process

Following sections outline steps (summarised) for each pipeline route.

### Feature branches
When a feature branch is pushed, jenkins will perform the following steps:
- Clean workspace (jenkins workspace)
- Checks out repository branch
- Runs the test `dockerfile` provided in the `JenkinsFile`
- Extracts and publishes test results
- (optional) Pings message to slack 

### Develop branch
On push event (triggered by feature branch or release branch merging):
- Clean workspace (jenkins workspace)
- Checks out repository branch
- Runs the test `dockerfile` provided in the `JenkinsFile`
- Extracts and publishes test results
- (optional) Pings message to slack 

### Release branches
On push event (triggered by start of release):
- Clean workspace (jenkins workspace)
- Checks out repository branch
- Runs the test `dockerfile` provided in the `JenkinsFile`
- Extracts and publishes test results
- Requests unique release number from separate jenkins job (auto-incrementing number)
- Updates project config with release number and commits change with automated commit message (Maven = pom.xml, gulp = config.js)
- Builds docker image using `Dockerfile` in root of project and docker tags image with unique release number. Tag is suffixed with `-release-candidate` e.g. `1.0.0-release-candidate`.
- Push docker (image) & project changes (pom.xml / config.js)
- (optional) Pings message to slack 

Release is ready to be deployed

### Master branch
On push event (triggered by finish of release):
- Clean workspace (jenkins workspace)
- Checks out repository branch
- Runs the test `dockerfile` provided in the `JenkinsFile`
- Extracts and publishes test results
- Retags image removing suffix e.g. `1.0.0`
- Push new docker tag
- (optional) Pings message to slack 

### Hotfix branches
On push event:
- Clean workspace (jenkins workspace)
- Checks out repository branch
- Runs the test `dockerfile` provided in the `JenkinsFile`
- Extracts and publishes test results
- Requests unique `patch` release number from separate jenkins job (auto-incrementing number)
- Updates project config with release number and commits change with automated commit message (Maven = pom.xml, gulp = config.js)
- Builds docker image using `Dockerfile` in root of project and docker tags image with unique release number. 
- Push docker (image) & project changes (pom.xml / config.js)
- (optional) Pings message to slack 

## Governance 

Gitflow jobs have been created in jenkins (e.g. Start Release, Finish Release). Configuration has been added to demonstrate how we could use approves to prevent certain actions happening. e.g. Requiring testers to sign release off release prior to running `Finish Release` job


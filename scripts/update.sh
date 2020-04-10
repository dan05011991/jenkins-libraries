
IMAGE=$(echo $1 | sed 's/\//\\\//g')
COMPOSE_FILE=$2
PROJECT_DIR=$3
REMOTE_BRANCH=$4


SNAPSHOT=$(mvn -f $PROJECT_DIR/pom.xml -q -Dexec.executable=echo -Dexec.args='${project.version}' --non-recursive exec:exec)

sed -i -E "s/$IMAGE.+/$IMAGE$SNAPSHOT/" $COMPOSE_FILE

if [ $(git diff | wc -l) -gt 0 ]; then
    git add docker-compose.yaml
    git commit -m "New release"
    git push origin $REMOTE_BRANCH
fi

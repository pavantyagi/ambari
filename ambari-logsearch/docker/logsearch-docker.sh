#!/bin/bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License

sdir="`dirname \"$0\"`"
: ${1:?"argument is missing: (start|stop|build-and-run|build|build-docker-and-run|build-mvn-and-run|build-docker-only|build-mvn-only)"}
command="$1"

function build_logsearch_project() {
  pushd $sdir/../
  mvn clean package -DskipTests
  popd
}

function build_logsearch_container() {
  pushd $sdir
  docker build -t ambari-logsearch:v1.0 .
  popd
}

function get_docker_ip() {
  local ip=$(ifconfig en0 | grep inet | awk '$1=="inet" {print $2}')
  echo $ip
}

function start_logsearch_container() {
  setup_profile
  source $sdir/Profile
  pushd $sdir/../../
  local AMBARI_LOCATION=$(pwd)
  popd
  : ${MAVEN_REPOSITORY_LOCATION:?"Please set the MAVEN_REPOSITORY_LOCATION in Profile"}
  kill_logsearch_container
  local docker_ip=$(get_docker_ip)
  echo "Run Log Search container"
  docker run -d --name logsearch --hostname logsearch.apache.org -e DISPLAY=$docker_ip:0 \
    -v $AMBARI_LOCATION:/root/ambari -v $MAVEN_REPOSITORY_LOCATION:/root/.m2 $LOGSEARCH_EXPOSED_PORTS $LOGSEARCH_ENV_OPTS $LOGSEARCH_EXTRA_OPTS $LOGSEARCH_VOLUME_OPTS -p 9983:9983 -p 4444:4444 -p 5910:5910 \
    -v $AMBARI_LOCATION/ambari-logsearch/ambari-logsearch-logfeeder/target/classes:/root/ambari/ambari-logsearch/ambari-logsearch-logfeeder/target/package/classes \
    -v $AMBARI_LOCATION/ambari-logsearch/ambari-logsearch-server/target/classes:/root/ambari/ambari-logsearch/ambari-logsearch-server/target/package/classes \
    -v $AMBARI_LOCATION/ambari-logsearch/ambari-logsearch-web/src/main/webapp:/root/ambari/ambari-logsearch/ambari-logsearch-server/target/package/classes/webapps/app \
    -v $AMBARI_LOCATION/ambari-logsearch/ambari-logsearch-web/target/libs:/root/ambari/ambari-logsearch/ambari-logsearch-server/target/package/classes/webapps/app/libs/bower \
    ambari-logsearch:v1.0
  ip_address=$(docker inspect --format '{{ .NetworkSettings.IPAddress }}' logsearch)
  echo "Log Search container started on $ip_address (for Mac OSX route to boot2docker/docker-machine VM address, e.g.: 'sudo route add -net 172.17.0.0/16 192.168.59.103')"
  echo "You can follow Log Search logs with 'docker logs -f logsearch' command"
}

function setup_profile() {
  if [ -f "$sdir/Profile" ];
  then
    echo "Profile file exists"
  else
    echo "Profile does not exist, Creating a new one..."
    pushd $sdir/../../
    local AMBARI_LOCATION=$(pwd)
    popd
    cat << EOF > $sdir/Profile
MAVEN_REPOSITORY_LOCATION=$HOME/.m2
LOGSEARCH_EXPOSED_PORTS="-p 8886:8886 -p 61888:61888 -p 5005:5005 -p 5006:5006"
LOGSEARCH_ENV_OPTS="-e LOGFEEDER_DEBUG_SUSPEND=n -e LOGSEARCH_DEBUG_SUSPEND=n -e COMPONENT_LOG=logsearch -e LOGSEARCH_HTTPS_ENABLED=false -e LOGSEARCH_SOLR_SSL_ENABLED=false -e GENERATE_KEYSTORE_AT_START=false"

LOGSEARCH_VOLUME_OPTS="-v $AMBARI_LOCATION/ambari-logsearch/docker/test-logs:/root/test-logs -v $AMBARI_LOCATION/ambari-logsearch/docker/test-config:/root/test-config"

LOGSEARCH_EXTRA_OPTS=""
EOF
    echo "Profile has been created. Check it out before starting Log Search. ($sdir/Profile)"
    exit
  fi;
}

function kill_logsearch_container() {
  echo "Try to remove logsearch container if exists..."
  docker rm -f logsearch
}

case $command in
  "build-and-run")
     build_logsearch_project
     build_logsearch_container
     start_logsearch_container
     ;;
  "build")
     build_logsearch_project
     build_logsearch_container
     ;;
  "build-docker-and-run")
     build_logsearch_container
     start_logsearch_container
     ;;
  "build-mvn-and-run")
     build_logsearch_project
     start_logsearch_container
     ;;
  "build-docker-only")
     build_logsearch_container
     ;;
  "build-mvn-only")
     build_logsearch_project
     ;;
  "start")
     start_logsearch_container
     ;;
  "stop")
     kill_logsearch_container
     ;;
   *)
   echo "Available commands: (start|stop|build-and-run|build|build-docker-and-run|build-mvn-and-run|build-docker-only|build-mvn-only)"
   ;;
esac

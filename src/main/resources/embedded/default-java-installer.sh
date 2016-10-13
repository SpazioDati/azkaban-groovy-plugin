#!/usr/bin/env bash

set -eu
set -o pipefail

function retry() {
    local n=0
    local try=$1
    local cmd="${@: 2}"
    [[ $# -le 1 ]] && {
        echo "Usage $0 <retry_number> <Command>"; }

    until [[ $n -ge $try ]]
    do
        $cmd && break || {
            echo "Command Fail.."
            ((n+=1))
            echo "retry $n ::"
            sleep 1;
        }
    done
}

DISTRO=$(cat /etc/*-release | grep 'DISTRIB_CODENAME' | grep -oE '[a-z]+')
WAIT_TIME=5
echo "Our distro is $DISTRO"
echo "deb http://ppa.launchpad.net/webupd8team/java/ubuntu $DISTRO main" | tee -a /etc/apt/sources.list
echo "deb-src http://ppa.launchpad.net/webupd8team/java/ubuntu $DISTRO main" | tee -a /etc/apt/sources.list
retry 3 apt-key adv --keyserver keyserver.ubuntu.com --recv-keys EEA14886
echo "Waiting for $WAIT_TIME seconds..."
sleep $WAIT_TIME
retry 3 apt-get -q update
echo oracle-java7-installer shared/accepted-oracle-license-v1-1 select true | /usr/bin/debconf-set-selections
retry 3 apt-get -qq -y install oracle-java7-installer
update-java-alternatives -s java-7-oracle
retry 3 apt-get -qq -y install oracle-java7-set-default

#!/bin/bash

DISTRO=$(cat /etc/*-release | grep 'DISTRIB_CODENAME' | grep -oE '[a-z]+')
echo "Our distro is $DISTRO"
echo "deb http://ppa.launchpad.net/webupd8team/java/ubuntu $DISTRO main" | tee -a /etc/apt/sources.list
echo "deb-src http://ppa.launchpad.net/webupd8team/java/ubuntu $DISTRO main" | tee -a /etc/apt/sources.list
apt-key adv --keyserver keyserver.ubuntu.com --recv-keys EEA14886
apt-get -q update
echo oracle-java7-installer shared/accepted-oracle-license-v1-1 select true | /usr/bin/debconf-set-selections
apt-get -qy install oracle-java7-installer
update-java-alternatives -s java-7-oracle
apt-get -qy install oracle-java7-set-default

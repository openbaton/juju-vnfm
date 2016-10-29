#!/bin/bash

source gradle.properties

_openbaton_base="/opt/openbaton"
_juju_vnfm_base="${_openbaton_base}/juju-vnfm"
_openbaton_config_file="/etc/openbaton/juju-vnfm.properties"
_version=${version}
_screen_name="juju-vnfm"


function checkBinary {
  echo -n " * Checking for '$1'..."
  if command -v $1 >/dev/null 2>&1; then
     echo "OK"
     return 0
   else
     echo >&2 "FAILED."
     return 1
   fi
}


_ex='sh -c'
if [ "$_user" != 'root' ]; then
    if checkBinary sudo; then
        _ex='sudo -E sh -c'
    elif checkBinary su; then
        _ex='su -c'
    fi
fi

function check_already_running {
    pgrep -f juju-vnfm-${_version}.jar
    if [ "$?" -eq "0" ]; then
        echo "Juju-VNFM is already running.."
        exit;
    fi
}

function start {
    echo "Starting the Juju-VNFM"
    # if not compiled, compile
    if [ ! -d ${_juju_vnfm_base}/build/  ]
        then
            compile
    fi
    check_already_running
    screen -ls | grep -v "No Sockets found" | grep -q openbaton
    screen_exists=$?
    if [ "${screen_exists}" -ne "0" ]; then
	    echo "Starting the Juju VNFM Adapter in a new screen session (attach to the screen with screen -x openbaton)"
	    if [ -f ${_openbaton_config_file} ]; then
            screen -c screenrc -d -m -S openbaton -t ${_screen_name} java -jar "${_juju_vnfm_base}/build/libs/juju-vnfm-${_version}.jar" --spring.config.location=file:${_openbaton_config_file}
        else
            screen -c screenrc -d -m -S openbaton -t ${_screen_name} java -jar "${_juju_vnfm_base}/build/libs/juju-vnfm-${_version}.jar"
        fi
    elif [ "${screen_exists}" -eq "0" ]; then
        echo "Starting the Juju VNFM Adapter in the existing screen session (attach to the screen with screen -x openbaton)"
        if [ -f ${_openbaton_config_file} ]; then
            screen -S openbaton -X screen -t ${_screen_name} java -jar "${_juju_vnfm_base}/build/libs/juju-vnfm-${_version}.jar" --spring.config.location=file:${_openbaton_config_file}
        else
            screen -S openbaton -X screen -t ${_screen_name} java -jar "${_juju_vnfm_base}/build/libs/juju-vnfm-${_version}.jar"
        fi
    fi
}

function stop {
    if screen -list | grep "openbaton"; then
	    screen -S openbaton -p ${_screen_name} -X stuff '\003'
    fi
}

function restart {
    kill
    start
}


function kill {
    pkill -f juju-vnfm-${_version}.jar
}


function compile {
    ./gradlew goJF build -x test
}

function tests {
    ./gradlew test
}

function clean {
    ./gradlew clean
}

function end {
    exit
}
function usage {
    echo -e "Open-Baton Juju VNFM Adapter\n"
    echo -e "Usage:\n\t ./juju-vnfm.sh [compile|start|stop|test|kill|clean]"
}

##
#   MAIN
##

if [ $# -eq 0 ]
   then
        usage
        exit 1
fi

declare -a cmds=($@)
for (( i = 0; i <  ${#cmds[*]}; ++ i ))
do
    case ${cmds[$i]} in
        "clean" )
            clean ;;
        "sc" )
            clean
            compile
            start ;;
        "start" )
            start ;;
        "stop" )
            stop ;;
        "restart" )
            restart ;;
        "compile" )
            compile ;;
        "kill" )
            kill ;;
        "test" )
            tests ;;
        * )
            usage
            end ;;
    esac
    if [[ $? -ne 0 ]]; 
    then
	    exit 1
    fi
done


#!/bin/bash

# 인자가 주어지고, 첫 번째 인자가 실행 가능한 파일이 아니면 IP/URL로 사용합니다.
if [ $# -gt 0 ] && [ ! -x "$1" ]; then
    IP_ADDR=$1
    shift
    echo "입력된 인자를 IP/URL로 사용합니다: $IP_ADDR"
else
    # en0 인터페이스에서 IP 주소를 추출합니다.
    IP_ADDR=$(ifconfig en0 | grep "inet " | awk '{print $2}' | head -n 1)
fi

if [ -z "$IP_ADDR" ]; then
    echo "오류: IP 주소를 찾을 수 없습니다 (인자 누락 및 en0 인터페이스 확인 실패)."
    exit 1
fi

# 환경 변수 설정 (http:// 또는 https:// 가 포함되어 있지 않으면 http:// 추가)
if [[ "$IP_ADDR" =~ ^https?:// ]]; then
    export SMS_TRANSMITTER_BASE_URL="$IP_ADDR"
else
    export SMS_TRANSMITTER_BASE_URL="http://$IP_ADDR"
fi

echo "환경 변수가 설정되었습니다: SMS_TRANSMITTER_BASE_URL=$SMS_TRANSMITTER_BASE_URL"

# 만약 남은 인자가 주어지면 해당 명령어를 실행합니다. (예: ./setup_env.sh 192.168.0.5 ./gradlew installDebug)
if [ $# -gt 0 ]; then
    exec "$@"
else
    echo "팁: 'source ./setup_env.sh [IP/URL]' 명령어를 사용하여 현재 쉘 세션에 적용하거나,"
    echo "     './setup_env.sh [IP/URL] ./gradlew assembleDebug'와 같이 실행 명령어를 뒤에 붙여 사용하세요."
fi

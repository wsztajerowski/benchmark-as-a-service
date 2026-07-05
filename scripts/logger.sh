#!/bin/bash

# Define color codes
RED='\033[1;31m'
GREEN='\033[1;32m'
YELLOW='\033[1;33m'
BLUE='\033[1;34m'
CYAN='\033[1;36m'
PURPLE='\033[1;35m'
NC='\033[0m' # No Color

# Logger function with dynamic client name based on script name
log() {
    local level=$1
    local message=$2
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')

    # Set LOGGER_NAME to the name of the script if not explicitly defined
    local logger_name=${LOGGER_NAME:-$(basename "$0" | sed 's/\.sh$//')}

    case $level in
        INFO)
            echo -e "${CYAN}[${logger_name}]${NC} ${BLUE}[INFO]${NC} ${PURPLE}$timestamp:${NC} $message"
            ;;
        SUCCESS)
            echo -e "${CYAN}[${logger_name}]${NC} ${GREEN}[SUCCESS]${NC} ${PURPLE}$timestamp:${NC} $message"
            ;;
        WARNING)
            echo -e "${CYAN}[${logger_name}]${NC} ${YELLOW}[WARNING]${NC} ${PURPLE}$timestamp:${NC} $message"
            ;;
        ERROR)
            echo -e "${CYAN}[${logger_name}]${NC} ${RED}[ERROR]${NC} ${PURPLE}$timestamp:${NC} $message"
            ;;
        *)
            echo -e "${CYAN}[${logger_name}]${NC} ${PURPLE}$timestamp:${NC} $message"
            ;;
    esac
}

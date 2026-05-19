#!/bin/bash

# ==============================================================================
# REMOTE CONTROL ACTIONS INTERCEPTOR LAYER
# ==============================================================================
GATEWAY_IP=$(route -n get default 2>/dev/null | awk '/gateway:/ {print $2}')
#GATEWAY_IP="10.83.154.225"

if [ "$1" == "trigger-monitor" ] && [ ! -z "$GATEWAY_IP" ]; then
    curl -s -X POST "http://${GATEWAY_IP}:9876/start-monitor" > /dev/null
    exit 0
fi

if [ "$1" == "trigger-refresh" ] && [ ! -z "$GATEWAY_IP" ]; then
    curl -s -X POST "http://${GATEWAY_IP}:9876/start-refresh" > /dev/null
    exit 0
fi

if [ "$1" == "trigger-stop" ] && [ ! -z "$GATEWAY_IP" ]; then
    curl -s -X POST "http://${GATEWAY_IP}:9876/stop-engine" > /dev/null
    exit 0
fi

# ==============================================================================
# HARDWARE CAPTURE & DATA INGESTION PIPELINE
# ==============================================================================
WIFI_INT=$(networksetup -listallhardwareports 2>/dev/null | awk '/Wi-Fi|AirPort/ {get_next=1; next} get_next {print $2; exit}')

if [ -z "$WIFI_INT" ] || ! ifconfig "$WIFI_INT" 2>/dev/null | grep -q "status: active"; then
    echo ""
    exit 0
fi

# REQUIREMENT 4a: Server mode disabled or phone unreachable
if [ -z "$GATEWAY_IP" ]; then
    echo "" # Explicitly empty status bar output string
    exit 0
fi

JSON_DATA=$(curl --connect-timeout 0.5 --max-time 1 -s "http://${GATEWAY_IP}:9876/status")

# REQUIREMENT 4a (Fallback): Phone server un-routable or off
if [ -z "$JSON_DATA" ]; then
    echo "X | color=red"
    echo "---"
    echo "GBars mobile service not detected"
    echo "---"
    exit 0
fi

# FIXED: Precision extraction using character delimiters instead of open letter matching
SERVICE_RUNNING=$(echo "$JSON_DATA" | grep -o '"serviceRunning":[a-z]*' | cut -d':' -f2)
ACTIVE_MODE=$(echo "$JSON_DATA" | grep -o '"activeMode":"[^"]*"' | cut -d'"' -f4)
NETWORK_MODE=$(echo "$JSON_DATA" | grep -o '"networkMode":"[^"]*"' | cut -d'"' -f4)

# ==============================================================================
# CONDITIONAL SYSTEM TRAY UI RENDERING DOCK
# ==============================================================================
if [ "$SERVICE_RUNNING" == "false" ]; then
    # REQUIREMENT 4b: Server Mode active, but telemetry engine is resting
    echo "😴"
    echo "---"
    echo "Phone Processing Engine: IDLE"
    echo "---"
    echo "Enable Real-time Monitoring Mode | bash=$0 param1=trigger-monitor terminal=false refresh=true color=#00FF00"
    echo "Enable Passive Link Refresh Mode | bash=$0 param1=trigger-refresh terminal=false refresh=true color=#00FFFF"
else
    if [ "$ACTIVE_MODE" == "REFRESH" ]; then
        # REQUIREMENT 4c: Server active and app running in refresh mode
        echo "🔁"
        echo "---"
        echo "Phone Processing Engine: REFRESH MODE"
        echo "---"
        echo "Switch to Active Monitor Loop | bash=$0 param1=trigger-monitor terminal=false refresh=true color=#00FF00"
        echo "Stop Passive Refresh Mode | bash=$0 param1=trigger-stop terminal=false refresh=true color=#FF4500"
    else
        # Formulate the Menu Bar Icon layout based on live telemetry properties
        if [ "$SERVICE_RUNNING" = "true" ]; then
            # REFINEMENT: Sanitize and map complex carrier strings into unified minimalist badges
            # Convert string to lowercase for bulletproof pattern matching
            NET_LOWER=$(echo "$NETWORK_MODE" | tr '[:upper:]' '[:lower:]')

            case "$NET_LOWER" in
                *4g*)
                    DISPLAY_MODE="ᯤ4G"
                    ;;
                *5g*)
                    DISPLAY_MODE="ᯤ5G"
                    ;;
                *3g*|*hspa*|*umts*)
                    DISPLAY_MODE="ᯤ3G"
                    ;;
                *2g*|*edge*|*gprs*)
                    DISPLAY_MODE="ᯤ2G"
                    ;;
                *disconnected*|*none*)
                    DISPLAY_MODE="ᯤ📵"
                    ;;
                *)
                    # Fallback to the raw string if an unexpected signature passes through
                    DISPLAY_MODE="$NETWORK_MODE"
                    ;;
            esac

            echo "$DISPLAY_MODE"
        else
            # Render idle remote connection link signature
            echo "🔗"
        fi
        echo "---"
        echo "Phone Processing Engine: MONITOR MODE"
        echo "---"
        echo "Switch to Passive Refresh Mode | bash=$0 param1=trigger-refresh terminal=false refresh=true color=#00FFFF"
        echo "Stop Cellular Monitor Loop | bash=$0 param1=trigger-stop terminal=false refresh=true color=#FF4500"
    fi
fi
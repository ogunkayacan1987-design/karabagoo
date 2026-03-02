#!/bin/bash
# Okul Mesajlasma Sistemi - Launcher Script
# Pardus/Debian uyumlu baslatici

APP_DIR="/usr/share/okul-mesajlasma"
APP_BIN="$APP_DIR/okul_mesajlasma"
LOG_FILE="/tmp/okul-mesajlasma.log"

# Uygulamayi baslat
cd "$APP_DIR"
export LD_LIBRARY_PATH="$APP_DIR/lib:$LD_LIBRARY_PATH"
"$APP_BIN" "$@" 2>"$LOG_FILE"
EXIT_CODE=$?

# Crash olduysa bildir
if [ $EXIT_CODE -ne 0 ] && [ $EXIT_CODE -ne 130 ]; then
    ERROR_MSG="Uygulama beklenmedik bir hatayla kapandi.\n\nHata kodu: $EXIT_CODE\nLog dosyasi: $LOG_FILE"

    if [ -s "$LOG_FILE" ]; then
        LAST_LINES=$(tail -10 "$LOG_FILE")
        ERROR_MSG="$ERROR_MSG\n\nSon hata mesajlari:\n$LAST_LINES"
    fi

    if command -v zenity &>/dev/null; then
        zenity --error --title="Uygulama Hatasi" --text="$ERROR_MSG" --width=500 2>/dev/null
    fi
fi

exit $EXIT_CODE

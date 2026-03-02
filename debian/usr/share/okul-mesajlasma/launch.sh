#!/bin/bash
# Okul Mesajlasma Sistemi - Launcher Script
# Pardus/Debian uyumlu baslatici - bagimliliklari kontrol eder

APP_DIR="/usr/share/okul-mesajlasma"
APP_BIN="$APP_DIR/okul_mesajlasma"
LOG_FILE="/tmp/okul-mesajlasma.log"

# Gerekli kutuphaneleri kontrol et (dpkg yerine ldconfig/dosya kontrolu)
MISSING_LIBS=""

# GTK3 kontrolu
if ! ldconfig -p 2>/dev/null | grep -q "libgtk-3"; then
    MISSING_LIBS="$MISSING_LIBS libgtk-3-0"
fi

# GStreamer kontrolu
if ! ldconfig -p 2>/dev/null | grep -q "libgstreamer-1.0"; then
    MISSING_LIBS="$MISSING_LIBS libgstreamer1.0-0"
fi

# Eksik kutuphane varsa kullaniciya bildir
if [ -n "$MISSING_LIBS" ]; then
    MSG="Okul Mesajlasma Sistemi icin gerekli paketler eksik:\n\n$MISSING_LIBS\n\nLutfen terminalde su komutu calistirin:\n\nsudo apt-get install -f -y\n\nveya\n\nsudo apt install -y$MISSING_LIBS"

    if command -v zenity &>/dev/null; then
        zenity --error --title="Eksik Bagimlиlik" --text="$MSG" --width=400 2>/dev/null
    elif command -v xmessage &>/dev/null; then
        echo -e "$MSG" | xmessage -file -
    else
        echo "HATA: Eksik paketler:$MISSING_LIBS"
        echo "Calistirin: sudo apt-get install -f -y"
    fi
    exit 1
fi

# Uygulamayi baslat
cd "$APP_DIR"
export LD_LIBRARY_PATH="$APP_DIR/lib:$LD_LIBRARY_PATH"
"$APP_BIN" "$@" 2>"$LOG_FILE"
EXIT_CODE=$?

# Crash olduysa bildir
if [ $EXIT_CODE -ne 0 ] && [ $EXIT_CODE -ne 130 ]; then
    ERROR_MSG="Uygulama beklenmedik bir hatayla kapandi.\n\nHata kodu: $EXIT_CODE\nLog dosyasi: $LOG_FILE"

    if [ -s "$LOG_FILE" ]; then
        LAST_LINES=$(tail -5 "$LOG_FILE")
        ERROR_MSG="$ERROR_MSG\n\nSon hata mesajlari:\n$LAST_LINES"
    fi

    if command -v zenity &>/dev/null; then
        zenity --error --title="Uygulama Hatasi" --text="$ERROR_MSG" --width=500 2>/dev/null
    fi
fi

exit $EXIT_CODE

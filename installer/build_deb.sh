#!/bin/bash
set -e

APP_NAME="okul-mesajlasma"
VERSION="1.1.0"
ARCH="amd64"
BUILD_DIR="build/linux/x64/release/bundle"
DEB_DIR="build/deb/${APP_NAME}_${VERSION}_${ARCH}"

# Temizle
rm -rf "build/deb"

# Dizin yapisi olustur
mkdir -p "${DEB_DIR}/DEBIAN"
mkdir -p "${DEB_DIR}/usr/share/${APP_NAME}"
mkdir -p "${DEB_DIR}/usr/share/applications"
mkdir -p "${DEB_DIR}/usr/bin"
mkdir -p "${DEB_DIR}/etc/xdg/autostart"
mkdir -p "${DEB_DIR}/etc/polkit-1/localauthority/50-local.d"

# Flutter build ciktisini kopyala
cp -r "${BUILD_DIR}/"* "${DEB_DIR}/usr/share/${APP_NAME}/"

# DEBIAN kontrol dosyalarini kopyala
cp debian/DEBIAN/control "${DEB_DIR}/DEBIAN/"
cp debian/DEBIAN/postinst "${DEB_DIR}/DEBIAN/"
cp debian/DEBIAN/prerm "${DEB_DIR}/DEBIAN/"
chmod 755 "${DEB_DIR}/DEBIAN/postinst"
chmod 755 "${DEB_DIR}/DEBIAN/prerm"

# Versiyonu guncelle
sed -i "s/^Version:.*/Version: ${VERSION}/" "${DEB_DIR}/DEBIAN/control"

# Desktop dosyasini kopyala (uygulama menusu icin)
cp debian/usr/share/applications/okul-mesajlasma.desktop \
   "${DEB_DIR}/usr/share/applications/"

# Autostart desktop dosyasini kopyala
cp debian/etc/xdg/autostart/okul-mesajlasma.desktop \
   "${DEB_DIR}/etc/xdg/autostart/"

# Polkit kuralini kopyala (sifresiz kapatma icin)
cp debian/etc/polkit-1/localauthority/50-local.d/okul-mesajlasma-shutdown.pkla \
   "${DEB_DIR}/etc/polkit-1/localauthority/50-local.d/"

# Symlink olustur
ln -sf "/usr/share/${APP_NAME}/okul_mesajlasma" "${DEB_DIR}/usr/bin/${APP_NAME}"

# .deb paketi olustur
dpkg-deb --build "${DEB_DIR}"

echo ""
echo "================================================"
echo "DEB paketi olusturuldu: build/deb/${APP_NAME}_${VERSION}_${ARCH}.deb"
echo "Pardus'ta kurmak icin: sudo dpkg -i ${APP_NAME}_${VERSION}_${ARCH}.deb"
echo "================================================"

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Karabağ H.Ö.Akarsel Ortaokulu - Lisans Anahtarı Oluşturucu
"""

# Gizli bilgiler (değiştirme!)
SCHOOL_CODE = "KBOA"
SECRET_KEY = "HatipoğluÖmerAkarsel2024"
CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

def generate_verification_code(year, month_day):
    """Doğrulama kodunu hesapla"""
    input_str = f"{SCHOOL_CODE}{year}{month_day}{SECRET_KEY}"
    bytes_data = input_str.encode('utf-8')

    hash_val = 0
    for byte in bytes_data:
        hash_val = ((hash_val << 5) - hash_val + byte) & 0xFFFFFFFF

    code = ""
    for i in range(4):
        code += CHARS[(hash_val >> (i * 5)) & 31]

    return code

def generate_license_key(year, month, day):
    """Lisans anahtarı oluştur"""
    month_day = f"{month:02d}{day:02d}"
    verification = generate_verification_code(str(year), month_day)
    return f"KBOA-{year}-{month_day}-{verification}"

def main():
    print("=" * 50)
    print("  KARABAĞ H.Ö.AKARSEL ORTAOKULU")
    print("  Lisans Anahtarı Oluşturucu")
    print("=" * 50)
    print()

    while True:
        print("Lisans bitiş tarihini girin:")

        try:
            year = int(input("  Yıl (örn: 2027): "))
            month = int(input("  Ay (1-12): "))
            day = int(input("  Gün (1-31): "))

            # Basit doğrulama
            if year < 2024 or year > 2100:
                print("Hata: Yıl 2024-2100 arasında olmalı!\n")
                continue
            if month < 1 or month > 12:
                print("Hata: Ay 1-12 arasında olmalı!\n")
                continue
            if day < 1 or day > 31:
                print("Hata: Gün 1-31 arasında olmalı!\n")
                continue

            # Anahtar oluştur
            license_key = generate_license_key(year, month, day)

            print()
            print("=" * 50)
            print(f"  Bitiş Tarihi: {day:02d}/{month:02d}/{year}")
            print()
            print(f"  LİSANS ANAHTARI: {license_key}")
            print("=" * 50)
            print()

            # Tekrar sor
            again = input("Başka anahtar oluşturmak ister misiniz? (e/h): ")
            if again.lower() != 'e':
                break
            print()

        except ValueError:
            print("Hata: Geçerli bir sayı girin!\n")
        except KeyboardInterrupt:
            print("\nÇıkış...")
            break

    print("\nProgram sonlandı.")

if __name__ == "__main__":
    main()

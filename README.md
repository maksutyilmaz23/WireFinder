# Wire Finder

Android uygulaması: telefonun manyetometresinden X/Y/Z verilerini okuyup toplam manyetik alanı µT cinsinden gösterir.

## Özellikler
- Canlı manyetometre ölçümü
- X/Y/Z değerleri
- Toplam manyetik alan: sqrt(X²+Y²+Z²)
- Referans (baseline) alma
- Manyetik alan değişimi (ΔB)
- Basit anomali uyarısı
- Canlı grafik
- İnternetsiz çalışma

## Önemli güvenlik notu
Bu uygulama elektrik kablosunu kesin olarak tespit etmez. Sadece manyetik alan anomalilerini gösterir. Profesyonel elektrik hattı dedektörünün yerine geçmez.

## Android Studio
Projeyi Android Studio'da açın. Gradle senkronizasyonundan sonra çalıştırabilirsiniz.


## v2 — Duvar Tarama Haritası
- "Duvarı Tara" modu
- Referans değerine göre manyetik alan farkı (ΔB)
- Telefon duvar üzerinde hareket ettikçe renkli tarama noktaları
- Yeşil: düşük değişim
- Sarı: orta değişim
- Turuncu: yüksek değişim
- Kırmızı: güçlü manyetik anomali
- Canlı manyetik alan grafiği ile birlikte çalışır

Not: Harita, telefonun hareketini gerçek fiziksel santimetre koordinatına dönüştürmez; tarama sırasındaki ölçümleri bir ızgara üzerinde görselleştirir. Kablo tespiti garanti edilmez.


## v3 — Düzenli Duvar Tarama
- Yatay / Dikey tarama yönü seçimi
- Tarama ilerleme göstergesi
- Tarama boyunca maksimum manyetik alan değişimi
- Daha düzenli ızgara yerleşimi
- 1440 noktaya kadar tarama geçmişi
- Tarama hattının devamlı görselleştirilmesi

Kullanım:
1. Duvarın yakınında, mümkünse metalden uzak bir noktada Referans Al.
2. Yatay veya Dikey yön seç.
3. Duvarı Tara'ya bas.
4. Telefonu duvara yakın ve sabit mesafede yavaşça seçilen yönde gezdir.
5. Haritadaki sürekli turuncu/kırmızı çizgiler manyetik alan anomalisi bölgelerini gösterir.

Bu uygulama elektrik kablosunu kesin olarak tespit etmez.

-- =====================================================
-- ByteCore / bytecore.ee-style test data seed
--
-- Not a Flyway migration: lives outside db/migration so it is never
-- applied automatically. Run explicitly via the Gradle "testData" task.
-- Wipes and repopulates every table with a sizeable, realistic dataset.
-- =====================================================

TRUNCATE TABLE
    wishlist_items,
    wishlists,
    cart_items,
    carts,
    order_items,
    payment_details,
    orders,
    inventory,
    warehouses,
    product_categories,
    product_variants,
    products,
    categories,
    user_payment_methods,
    user_address,
    users
    RESTART IDENTITY CASCADE;

-- =====================================================
-- Users
-- =====================================================

INSERT INTO users (id, role, username, email, password_hash, date_of_birth) VALUES
(1, 'ADMIN', 'admin', 'admin@bytecore.ee', '$2a$10$N9qo8uLOickgx2ZMRZoMy.Mrq4Q8x6q6q6q6q6q6q6q6q6q6q6q6q', '1985-02-11'),
(2, 'SUPPORT', 'kadri.support', 'kadri.tamm@bytecore.ee', '$2a$10$N9qo8uLOickgx2ZMRZoMy.Mrq4Q8x6q6q6q6q6q6q6q6q6q6q6q6q', '1990-06-23'),
(3, 'USER', 'mart.saar', 'mart.saar@gmail.com', '$2a$10$N9qo8uLOickgx2ZMRZoMy.Mrq4Q8x6q6q6q6q6q6q6q6q6q6q6q6q', '1992-01-15'),
(4, 'USER', 'liisa.kask', 'liisa.kask@gmail.com', '$2a$10$N9qo8uLOickgx2ZMRZoMy.Mrq4Q8x6q6q6q6q6q6q6q6q6q6q6q6q', '1988-09-02'),
(5, 'USER', 'jaan.tamm', 'jaan.tamm@hot.ee', '$2a$10$N9qo8uLOickgx2ZMRZoMy.Mrq4Q8x6q6q6q6q6q6q6q6q6q6q6q6q', '1995-11-30'),
(6, 'USER', 'anna.mets', 'anna.mets@gmail.com', '$2a$10$N9qo8uLOickgx2ZMRZoMy.Mrq4Q8x6q6q6q6q6q6q6q6q6q6q6q6q', '1999-03-18'),
(7, 'USER', 'peeter.oja', 'peeter.oja@mail.ee', '$2a$10$N9qo8uLOickgx2ZMRZoMy.Mrq4Q8x6q6q6q6q6q6q6q6q6q6q6q6q', '1983-07-07'),
(8, 'USER', 'kristiina.laur', 'kristiina.laur@gmail.com', '$2a$10$N9qo8uLOickgx2ZMRZoMy.Mrq4Q8x6q6q6q6q6q6q6q6q6q6q6q6q', '1991-12-25'),
(9, 'USER', 'tanel.roos', 'tanel.roos@gmail.com', '$2a$10$N9qo8uLOickgx2ZMRZoMy.Mrq4Q8x6q6q6q6q6q6q6q6q6q6q6q6q', '1997-04-09'),
(10, 'USER', 'egle.viik', 'egle.viik@gmail.com', '$2a$10$N9qo8uLOickgx2ZMRZoMy.Mrq4Q8x6q6q6q6q6q6q6q6q6q6q6q6q', '1993-08-14'),
(11, 'USER', 'raul.kukk', 'raul.kukk@gmail.com', '$2a$10$N9qo8uLOickgx2ZMRZoMy.Mrq4Q8x6q6q6q6q6q6q6q6q6q6q6q6q', '1989-05-05'),
(12, 'USER', 'marta.lind', 'marta.lind@gmail.com', '$2a$10$N9qo8uLOickgx2ZMRZoMy.Mrq4Q8x6q6q6q6q6q6q6q6q6q6q6q6q', '2000-10-19'),
(13, 'USER', 'toomas.aas', 'toomas.aas@gmail.com', '$2a$10$N9qo8uLOickgx2ZMRZoMy.Mrq4Q8x6q6q6q6q6q6q6q6q6q6q6q6q', '1986-02-28'),
(14, 'USER', 'kati.pold', 'kati.pold@gmail.com', '$2a$10$N9qo8uLOickgx2ZMRZoMy.Mrq4Q8x6q6q6q6q6q6q6q6q6q6q6q6q', '1994-06-11'),
(15, 'USER', 'indrek.vaher', 'indrek.vaher@gmail.com', '$2a$10$N9qo8uLOickgx2ZMRZoMy.Mrq4Q8x6q6q6q6q6q6q6q6q6q6q6q6q', '1998-01-22');

INSERT INTO user_address (user_id, first_name, last_name, city, country, postal_code, address_line1, address_line2, mobile) VALUES
(3, 'Mart', 'Saar', 'Tallinn', 'Estonia', '10111', 'Narva mnt 5', NULL, '+37256123456'),
(4, 'Liisa', 'Kask', 'Tartu', 'Estonia', '50090', 'Riia 45', 'Korter 12', '+37255234567'),
(5, 'Jaan', 'Tamm', 'Tallinn', 'Estonia', '11312', 'Pärnu mnt 102', NULL, '+37251345678'),
(6, 'Anna', 'Mets', 'Pärnu', 'Estonia', '80010', 'Rüütli 8', NULL, '+37253456789'),
(7, 'Peeter', 'Oja', 'Tallinn', 'Estonia', '13619', 'Mustamäe tee 20', 'Korter 3', '+37252567890'),
(8, 'Kristiina', 'Laur', 'Tartu', 'Estonia', '51003', 'Kalevi 12', NULL, '+37254678901'),
(9, 'Tanel', 'Roos', 'Narva', 'Estonia', '20303', 'Peetri plats 4', NULL, '+37258789012'),
(10, 'Egle', 'Viik', 'Tallinn', 'Estonia', '10617', 'Sõpruse pst 200', NULL, '+37259890123'),
(11, 'Raul', 'Kukk', 'Viljandi', 'Estonia', '71020', 'Tallinna 33', NULL, '+37255901234'),
(12, 'Marta', 'Lind', 'Tallinn', 'Estonia', '10151', 'Tartu mnt 15', 'Korter 7', '+37256012345'),
(13, 'Toomas', 'Aas', 'Rakvere', 'Estonia', '44313', 'Vilde 2', NULL, '+37257123456'),
(14, 'Kati', 'Põld', 'Tallinn', 'Estonia', '11415', 'Laagna tee 46', NULL, '+37258234567');

INSERT INTO user_payment_methods (user_id, provider, type) VALUES
(3, 'Swedbank', 'BANK_TRANSFER'),
(3, 'Visa', 'CARD'),
(4, 'Mastercard', 'CARD'),
(5, 'SEB', 'BANK_TRANSFER'),
(6, 'Visa', 'CARD'),
(7, 'PayPal', 'DIGITAL_WALLET'),
(8, 'Mastercard', 'CARD'),
(9, 'LHV', 'BANK_TRANSFER'),
(10, 'Visa', 'CARD'),
(11, 'Apple Pay', 'DIGITAL_WALLET'),
(12, 'Mastercard', 'CARD'),
(13, 'Coop Pank', 'BANK_TRANSFER'),
(14, 'Visa', 'CARD');

-- =====================================================
-- Categories
-- =====================================================

INSERT INTO categories (id, name, slug, parent_id) VALUES
(1, 'Arvutid', 'arvutid', NULL),
(2, 'Sülearvutid', 'sulearvutid', NULL),
(3, 'Lauaarvutid', 'lauaarvutid', NULL),
(4, 'Komponendid', 'komponendid', NULL),
(5, 'Monitorid', 'monitorid', NULL),
(6, 'Perifeeria', 'perifeeria', NULL),
(7, 'Võrguseadmed', 'vorguseadmed', NULL),
(8, 'Nutitelefonid', 'nutitelefonid', NULL),
(9, 'Tarkvara', 'tarkvara', NULL),
(10, 'Mängurid', 'mangurid', NULL),
(11, 'Protsessorid', 'protsessorid', 4),
(12, 'Videokaardid', 'videokaardid', 4),
(13, 'Emaplaadid', 'emaplaadid', 4),
(14, 'Mälu', 'malu', 4),
(15, 'Kõvakettad', 'kovakettad', 4),
(16, 'Toiteplokid', 'toiteplokid', 4),
(17, 'Korpused', 'korpused', 4),
(18, 'Jahutus', 'jahutus', 4),
(19, 'Klaviatuurid', 'klaviatuurid', 6),
(20, 'Hiired', 'hiired', 6),
(21, 'Kõrvaklapid', 'korvaklapid', 6),
(22, 'Veebikaamerad', 'veebikaamerad', 6);

-- =====================================================
-- Products
-- =====================================================

INSERT INTO products (id, name, slug, description) VALUES
(1, 'Dell XPS 15', 'dell-xps-15', 'Premium 15" sülearvuti loomeinimestele ja arendajatele.'),
(2, 'Lenovo ThinkPad X1 Carbon', 'lenovo-thinkpad-x1-carbon', 'Kerge ja vastupidav äriklassi sülearvuti.'),
(3, 'ASUS ROG Zephyrus G14', 'asus-rog-zephyrus-g14', 'Kompaktne mängusülearvuti võimsa jõudlusega.'),
(4, 'HP Pavilion 15', 'hp-pavilion-15', 'Igapäevaseks kasutuseks sobiv sülearvuti.'),
(5, 'Apple MacBook Air M3', 'apple-macbook-air-m3', 'Õhuke ja kerge sülearvuti Apple Silicon protsessoriga.'),
(6, 'MSI Stealth 16', 'msi-stealth-16', 'Elegantne mängusülearvuti RTX graafikaga.'),
(7, 'Acer Aspire 5', 'acer-aspire-5', 'Taskukohane sülearvuti õpilastele ja kontoritööks.'),
(8, 'ASUS ROG Strix G16', 'asus-rog-strix-g16', 'Võimas mängusülearvuti suure ekraaniga.'),
(9, 'Custom Gaming PC RTX 4070', 'custom-gaming-pc-rtx-4070', 'Valmis lauaarvuti mängimiseks ja striimimiseks.'),
(10, 'Custom Office PC i5', 'custom-office-pc-i5', 'Vaikne ja energiasäästlik kontoriarvuti.'),
(11, 'Mac Mini M2', 'mac-mini-m2', 'Kompaktne lauaarvuti Apple Silicon protsessoriga.'),
(12, 'AMD Ryzen 7 7800X3D', 'amd-ryzen-7-7800x3d', 'Mänguritele optimeeritud protsessor 3D V-Cache tehnoloogiaga.'),
(13, 'Intel Core i7-14700K', 'intel-core-i7-14700k', 'Kõrge jõudlusega protsessor mängimiseks ja tootlikkuseks.'),
(14, 'AMD Ryzen 5 7600', 'amd-ryzen-5-7600', 'Hinna ja kvaliteedi suhtelt hea protsessor.'),
(15, 'NVIDIA GeForce RTX 4070 Ti', 'nvidia-geforce-rtx-4070-ti', 'Kõrgjõudlusega videokaart 1440p ja 4K mängimiseks.'),
(16, 'NVIDIA GeForce RTX 4060', 'nvidia-geforce-rtx-4060', 'Efektiivne videokaart täishäid mängukogemuseks.'),
(17, 'AMD Radeon RX 7800 XT', 'amd-radeon-rx-7800-xt', 'Suure jõudlusega videokaart konkurentsivõimeliste hindadega.'),
(18, 'ASUS ROG Strix B650-E', 'asus-rog-strix-b650-e', 'AM5 emaplaat entusiastidele.'),
(19, 'MSI MAG B760 Tomahawk', 'msi-mag-b760-tomahawk', 'Usaldusväärne LGA1700 emaplaat.'),
(20, 'Corsair Vengeance 32GB DDR5', 'corsair-vengeance-32gb-ddr5', 'Kiire DDR5 mälukomplekt.'),
(21, 'Kingston Fury Beast 16GB DDR4', 'kingston-fury-beast-16gb-ddr4', 'Usaldusväärne DDR4 mälukomplekt.'),
(22, 'Samsung 990 Pro 2TB NVMe SSD', 'samsung-990-pro-2tb-nvme-ssd', 'Ülikiire PCIe 4.0 NVMe SSD.'),
(23, 'WD Black SN850X 1TB NVMe SSD', 'wd-black-sn850x-1tb-nvme-ssd', 'Mänguritele mõeldud kiire SSD.'),
(24, 'Seagate Barracuda 4TB HDD', 'seagate-barracuda-4tb-hdd', 'Suuremahuline andmesalvestuseks sobiv kõvaketas.'),
(25, 'Corsair RM850x PSU', 'corsair-rm850x-psu', '850W 80+ Gold sertifikaadiga toiteplokk.'),
(26, 'be quiet! Pure Power 12 M 750W', 'be-quiet-pure-power-12-m-750w', 'Vaikne ja efektiivne toiteplokk.'),
(27, 'NZXT H510 Flow', 'nzxt-h510-flow', 'Kompaktne ja hea õhuvooluga arvutikorpus.'),
(28, 'Fractal Design Meshify 2', 'fractal-design-meshify-2', 'Ruumikas korpus suurepärase jahutusega.'),
(29, 'Noctua NH-D15', 'noctua-nh-d15', 'Tippklassi õhkjahutus protsessorile.'),
(30, 'Corsair iCUE H150i Elite', 'corsair-icue-h150i-elite', '360mm vedelikjahutussüsteem.'),
(31, 'Samsung Odyssey G7 32"', 'samsung-odyssey-g7-32', 'Kõverad 240Hz mängumonitor.'),
(32, 'LG UltraGear 27GP850', 'lg-ultragear-27gp850', '27" 165Hz QHD mängumonitor.'),
(33, 'Dell UltraSharp U2723QE', 'dell-ultrasharp-u2723qe', '27" 4K monitor professionaalseks tööks.'),
(34, 'Logitech G Pro X Superlight', 'logitech-g-pro-x-superlight', 'Ülikerge juhtmevaba mängurihiir.'),
(35, 'Razer DeathAdder V3', 'razer-deathadder-v3', 'Ergonoomiline mängurihiir.'),
(36, 'Corsair K70 RGB Pro', 'corsair-k70-rgb-pro', 'Mehaaniline RGB mänguriklaviatuur.'),
(37, 'Keychron K8 Pro', 'keychron-k8-pro', 'Traadita mehaaniline klaviatuur.'),
(38, 'SteelSeries Arctis Nova 7', 'steelseries-arctis-nova-7', 'Traadita gaming-peakomplekt.'),
(39, 'Logitech BRIO 4K', 'logitech-brio-4k', '4K veebikaamera striimimiseks ja videokõnedeks.'),
(40, 'Apple iPhone 15', 'apple-iphone-15', 'Uusima põlvkonna nutitelefon Apple''ilt.'),
(41, 'Samsung Galaxy S24', 'samsung-galaxy-s24', 'Lipulaeva tasemel Android nutitelefon.'),
(42, 'TP-Link Archer AX55', 'tp-link-archer-ax55', 'Wi-Fi 6 ruuter koduvõrguks.');

INSERT INTO product_categories (product_id, category_id) VALUES
(1, 1), (1, 2),
(2, 1), (2, 2),
(3, 1), (3, 2), (3, 10),
(4, 1), (4, 2),
(5, 1), (5, 2),
(6, 1), (6, 2), (6, 10),
(7, 1), (7, 2),
(8, 1), (8, 2), (8, 10),
(9, 1), (9, 3), (9, 10),
(10, 1), (10, 3),
(11, 1), (11, 3),
(12, 4), (12, 11),
(13, 4), (13, 11),
(14, 4), (14, 11),
(15, 4), (15, 12),
(16, 4), (16, 12),
(17, 4), (17, 12),
(18, 4), (18, 13),
(19, 4), (19, 13),
(20, 4), (20, 14),
(21, 4), (21, 14),
(22, 4), (22, 15),
(23, 4), (23, 15),
(24, 4), (24, 15),
(25, 4), (25, 16),
(26, 4), (26, 16),
(27, 4), (27, 17),
(28, 4), (28, 17),
(29, 4), (29, 18),
(30, 4), (30, 18),
(31, 5), (31, 10),
(32, 5), (32, 10),
(33, 5),
(34, 6), (34, 20), (34, 10),
(35, 6), (35, 20), (35, 10),
(36, 6), (36, 19), (36, 10),
(37, 6), (37, 19),
(38, 6), (38, 21), (38, 10),
(39, 6), (39, 22),
(40, 8),
(41, 8),
(42, 7);

-- =====================================================
-- Product variants
-- =====================================================

INSERT INTO product_variants (id, product_id, sku, price, attributes, barcode, weight_grams, is_active) VALUES
(1, 1, 'DELL-XPS15-16-512', 1899.00, '{"cpu":"Intel Core i7-13700H","ram":"16GB","storage":"512GB SSD","color":"Silver"}', '4056123456781', 1900, TRUE),
(2, 1, 'DELL-XPS15-32-1TB', 2299.00, '{"cpu":"Intel Core i7-13700H","ram":"32GB","storage":"1TB SSD","color":"Silver"}', '4056123456782', 1900, TRUE),
(3, 2, 'LEN-X1C-16-512', 1799.00, '{"cpu":"Intel Core i7-1355U","ram":"16GB","storage":"512GB SSD","color":"Black"}', '4056123456783', 1120, TRUE),
(4, 3, 'ASUS-G14-16-1TB', 2099.00, '{"cpu":"AMD Ryzen 9 7940HS","gpu":"RTX 4060","ram":"16GB","storage":"1TB SSD","color":"Moonlight White"}', '4056123456784', 1650, TRUE),
(5, 4, 'HP-PAV15-8-256', 699.00, '{"cpu":"Intel Core i5-1235U","ram":"8GB","storage":"256GB SSD","color":"Natural Silver"}', '4056123456785', 1750, TRUE),
(6, 5, 'APL-MBA-M3-8-256', 1349.00, '{"cpu":"Apple M3","ram":"8GB","storage":"256GB SSD","color":"Midnight"}', '4056123456786', 1240, TRUE),
(7, 5, 'APL-MBA-M3-16-512', 1649.00, '{"cpu":"Apple M3","ram":"16GB","storage":"512GB SSD","color":"Starlight"}', '4056123456787', 1240, TRUE),
(8, 6, 'MSI-STEALTH16-32-1TB', 2599.00, '{"cpu":"Intel Core i9-13900H","gpu":"RTX 4070","ram":"32GB","storage":"1TB SSD","color":"Core Black"}', '4056123456788', 2100, TRUE),
(9, 7, 'ACER-A5-8-512', 599.00, '{"cpu":"AMD Ryzen 5 7530U","ram":"8GB","storage":"512GB SSD","color":"Silver"}', '4056123456789', 1700, TRUE),
(10, 8, 'ASUS-STRIXG16-16-1TB', 1899.00, '{"cpu":"Intel Core i7-13650HX","gpu":"RTX 4070","ram":"16GB","storage":"1TB SSD","color":"Eclipse Gray"}', '4056123456790', 2500, TRUE),
(11, 9, 'PC-GAMING-RTX4070', 1799.00, '{"cpu":"AMD Ryzen 7 7800X3D","gpu":"RTX 4070","ram":"32GB","storage":"1TB NVMe SSD"}', '4056123456791', 12000, TRUE),
(12, 10, 'PC-OFFICE-I5', 799.00, '{"cpu":"Intel Core i5-14400","ram":"16GB","storage":"512GB NVMe SSD"}', '4056123456792', 8500, TRUE),
(13, 11, 'APL-MACMINI-M2-8-256', 799.00, '{"cpu":"Apple M2","ram":"8GB","storage":"256GB SSD","color":"Silver"}', '4056123456793', 1260, TRUE),
(14, 12, 'AMD-7800X3D', 449.00, '{"cores":"8","threads":"16","socket":"AM5"}', '4056123456794', 90, TRUE),
(15, 13, 'INTEL-I7-14700K', 429.00, '{"cores":"20","threads":"28","socket":"LGA1700"}', '4056123456795', 100, TRUE),
(16, 14, 'AMD-7600', 219.00, '{"cores":"6","threads":"12","socket":"AM5"}', '4056123456796', 85, TRUE),
(17, 15, 'NV-RTX4070TI', 899.00, '{"memory":"12GB GDDR6X","interface":"PCIe 4.0"}', '4056123456797', 1350, TRUE),
(18, 16, 'NV-RTX4060', 349.00, '{"memory":"8GB GDDR6","interface":"PCIe 4.0"}', '4056123456798', 950, TRUE),
(19, 17, 'AMD-RX7800XT', 549.00, '{"memory":"16GB GDDR6","interface":"PCIe 4.0"}', '4056123456799', 1250, TRUE),
(20, 18, 'ASUS-B650E', 329.00, '{"socket":"AM5","formFactor":"ATX"}', '4056123456800', 1200, TRUE),
(21, 19, 'MSI-B760-TOMAHAWK', 189.00, '{"socket":"LGA1700","formFactor":"ATX"}', '4056123456801', 1150, TRUE),
(22, 20, 'CORSAIR-VEN-32-DDR5', 149.00, '{"capacity":"32GB","speed":"6000MHz","type":"DDR5"}', '4056123456802', 60, TRUE),
(23, 21, 'KINGSTON-FURY-16-DDR4', 59.00, '{"capacity":"16GB","speed":"3200MHz","type":"DDR4"}', '4056123456803', 40, TRUE),
(24, 22, 'SAMSUNG-990PRO-2TB', 189.00, '{"capacity":"2TB","interface":"PCIe 4.0 NVMe"}', '4056123456804', 45, TRUE),
(25, 23, 'WD-SN850X-1TB', 99.00, '{"capacity":"1TB","interface":"PCIe 4.0 NVMe"}', '4056123456805', 40, TRUE),
(26, 24, 'SEAGATE-BARRACUDA-4TB', 109.00, '{"capacity":"4TB","interface":"SATA III"}', '4056123456806', 650, TRUE),
(27, 25, 'CORSAIR-RM850X', 139.00, '{"wattage":"850W","rating":"80+ Gold"}', '4056123456807', 1900, TRUE),
(28, 26, 'BEQUIET-PP12M-750W', 99.00, '{"wattage":"750W","rating":"80+ Gold"}', '4056123456808', 1700, TRUE),
(29, 27, 'NZXT-H510-FLOW', 89.00, '{"formFactor":"ATX Mid Tower","color":"Black"}', '4056123456809', 5900, TRUE),
(30, 28, 'FRACTAL-MESHIFY2', 149.00, '{"formFactor":"ATX Mid Tower","color":"White"}', '4056123456810', 8800, TRUE),
(31, 29, 'NOCTUA-NHD15', 109.00, '{"type":"Air Cooler","socketSupport":"AM5/LGA1700"}', '4056123456811', 1320, TRUE),
(32, 30, 'CORSAIR-H150I-ELITE', 189.00, '{"type":"AIO Liquid Cooler","radiatorSize":"360mm"}', '4056123456812', 2200, TRUE),
(33, 31, 'SAMSUNG-ODYSSEY-G7-32', 749.00, '{"size":"32in","resolution":"2560x1440","refreshRate":"240Hz","panel":"VA"}', '4056123456813', 8200, TRUE),
(34, 32, 'LG-27GP850', 429.00, '{"size":"27in","resolution":"2560x1440","refreshRate":"165Hz","panel":"Nano IPS"}', '4056123456814', 5400, TRUE),
(35, 33, 'DELL-U2723QE', 599.00, '{"size":"27in","resolution":"3840x2160","refreshRate":"60Hz","panel":"IPS Black"}', '4056123456815', 6700, TRUE),
(36, 34, 'LOGI-GPROX-SUPERLIGHT', 149.00, '{"connectivity":"Wireless","weightGrams":"63","color":"Black"}', '4056123456816', 63, TRUE),
(37, 35, 'RAZER-DEATHADDER-V3', 69.00, '{"connectivity":"Wired","dpi":"30000","color":"Black"}', '4056123456817', 59, TRUE),
(38, 36, 'CORSAIR-K70-RGBPRO', 179.00, '{"switchType":"Cherry MX Red","layout":"Full-size","backlight":"RGB"}', '4056123456818', 1100, TRUE),
(39, 37, 'KEYCHRON-K8-PRO', 99.00, '{"switchType":"Gateron Brown","layout":"TKL","connectivity":"Wireless/Wired"}', '4056123456819', 850, TRUE),
(40, 38, 'STEELSERIES-ARCTIS-NOVA7', 179.00, '{"connectivity":"Wireless","batteryLifeHours":"38","color":"Black"}', '4056123456820', 335, TRUE),
(41, 39, 'LOGI-BRIO-4K', 149.00, '{"resolution":"4K","fov":"90 degrees"}', '4056123456821', 118, TRUE),
(42, 40, 'APL-IPHONE15-128-BLK', 999.00, '{"storage":"128GB","color":"Black"}', '4056123456822', 171, TRUE),
(43, 40, 'APL-IPHONE15-256-BLU', 1129.00, '{"storage":"256GB","color":"Blue"}', '4056123456823', 171, TRUE),
(44, 41, 'SAM-S24-256-GRY', 899.00, '{"storage":"256GB","color":"Onyx Gray"}', '4056123456824', 168, TRUE),
(45, 41, 'SAM-S24-512-VLT', 999.00, '{"storage":"512GB","color":"Cobalt Violet"}', '4056123456825', 168, TRUE),
(46, 42, 'TPLINK-AX55', 89.00, '{"wifiStandard":"Wi-Fi 6","bands":"Dual-band"}', '4056123456826', 420, TRUE);

-- =====================================================
-- Warehouses & inventory
-- =====================================================

INSERT INTO warehouses (id, name, location) VALUES
(1, 'Tallinna Ladu', 'Tallinn, Peterburi tee 46'),
(2, 'Tartu Ladu', 'Tartu, Ringtee 1'),
(3, 'Pärnu Ladu', 'Pärnu, Tallinna mnt 89');

INSERT INTO inventory (product_variant_id, warehouse_id, quantity)
SELECT v.id, w.id,
    (20 + ((v.id * 7 + w.id * 13) % 60))::int
FROM product_variants v
CROSS JOIN warehouses w;

-- =====================================================
-- Wishlists
-- =====================================================

INSERT INTO wishlists (id, user_id) VALUES
(1, 3), (2, 4), (3, 6), (4, 8), (5, 9), (6, 11), (7, 12), (8, 14);

INSERT INTO wishlist_items (wishlist_id, product_variant_id) VALUES
(1, 17), (1, 22), (1, 33),
(2, 6), (2, 41),
(3, 8), (3, 19), (3, 32),
(4, 24), (4, 25),
(5, 42), (5, 44),
(6, 10), (6, 17), (6, 38),
(7, 1), (7, 35),
(8, 40), (8, 45);

-- =====================================================
-- Carts
-- =====================================================

INSERT INTO carts (id, user_id) VALUES
(1, 3), (2, 5), (3, 6), (4, 7), (5, 10), (6, 13), (7, 15);

INSERT INTO cart_items (cart_id, product_variant_id, quantity) VALUES
(1, 17, 1), (1, 22, 2),
(2, 6, 1),
(3, 8, 1), (3, 32, 1),
(4, 36, 1), (4, 40, 1),
(5, 24, 1),
(6, 44, 1),
(7, 46, 1), (7, 25, 1);

-- =====================================================
-- Orders, order items, payments
-- =====================================================

INSERT INTO orders (id, user_id, cart_id, status, total_amount, created_at) VALUES
(1, 3, NULL, 'COMPLETED', 1048.00, NOW() - INTERVAL '60 days'),
(2, 4, NULL, 'COMPLETED', 1349.00, NOW() - INTERVAL '55 days'),
(3, 6, NULL, 'COMPLETED', 2948.00, NOW() - INTERVAL '48 days'),
(4, 7, NULL, 'CANCELLED', 149.00, NOW() - INTERVAL '40 days'),
(5, 8, NULL, 'COMPLETED', 208.00, NOW() - INTERVAL '35 days'),
(6, 9, NULL, 'SHIPPING', 999.00, NOW() - INTERVAL '10 days'),
(7, 10, NULL, 'COMPLETED', 428.00, NOW() - INTERVAL '25 days'),
(8, 11, NULL, 'PAID', 899.00, NOW() - INTERVAL '4 days'),
(9, 12, NULL, 'COMPLETED', 1899.00, NOW() - INTERVAL '20 days'),
(10, 13, NULL, 'PENDING', 178.00, NOW() - INTERVAL '1 days'),
(11, 14, NULL, 'COMPLETED', 749.00, NOW() - INTERVAL '15 days'),
(12, 15, NULL, 'COMPLETED', 269.00, NOW() - INTERVAL '8 days');

INSERT INTO order_items (order_id, product_variant_id, quantity, price_at_purchase) VALUES
(1, 17, 1, 899.00), (1, 39, 1, 99.00), (1, 37, 1, 50.00),
(2, 6, 1, 1349.00),
(3, 8, 1, 2599.00), (3, 41, 1, 149.00), (3, 22, 1, 200.00),
(4, 30, 1, 149.00),
(5, 27, 1, 139.00), (5, 25, 1, 69.00),
(6, 42, 1, 999.00),
(7, 34, 1, 429.00),
(8, 44, 1, 899.00),
(9, 10, 1, 1899.00),
(10, 23, 1, 59.00), (10, 46, 1, 89.00), (10, 37, 1, 30.00),
(11, 33, 1, 749.00),
(12, 22, 1, 149.00), (12, 46, 1, 89.00), (12, 37, 1, 31.00);

INSERT INTO payment_details (order_id, amount, provider, type, status, created_at) VALUES
(1, 1048.00, 'Swedbank', 'BANK_TRANSFER', 'SUCCESS', NOW() - INTERVAL '60 days'),
(2, 1349.00, 'Mastercard', 'CARD', 'SUCCESS', NOW() - INTERVAL '55 days'),
(3, 2948.00, 'Visa', 'CARD', 'SUCCESS', NOW() - INTERVAL '48 days'),
(4, 149.00, 'PayPal', 'DIGITAL_WALLET', 'FAILED', NOW() - INTERVAL '40 days'),
(5, 208.00, 'Mastercard', 'CARD', 'SUCCESS', NOW() - INTERVAL '35 days'),
(6, 999.00, 'LHV', 'BANK_TRANSFER', 'SUCCESS', NOW() - INTERVAL '10 days'),
(7, 428.00, 'Visa', 'CARD', 'SUCCESS', NOW() - INTERVAL '25 days'),
(8, 899.00, 'Apple Pay', 'DIGITAL_WALLET', 'PENDING', NOW() - INTERVAL '4 days'),
(9, 1899.00, 'Mastercard', 'CARD', 'SUCCESS', NOW() - INTERVAL '20 days'),
(10, 178.00, 'Coop Pank', 'BANK_TRANSFER', 'PENDING', NOW() - INTERVAL '1 days'),
(11, 749.00, 'Visa', 'CARD', 'SUCCESS', NOW() - INTERVAL '15 days'),
(12, 269.00, 'Visa', 'CARD', 'SUCCESS', NOW() - INTERVAL '8 days');

-- =====================================================
-- Sync sequences with explicitly inserted ids
-- =====================================================

SELECT setval(pg_get_serial_sequence('users', 'id'), (SELECT MAX(id) FROM users));
SELECT setval(pg_get_serial_sequence('user_address', 'id'), (SELECT MAX(id) FROM user_address));
SELECT setval(pg_get_serial_sequence('user_payment_methods', 'id'), (SELECT MAX(id) FROM user_payment_methods));
SELECT setval(pg_get_serial_sequence('categories', 'id'), (SELECT MAX(id) FROM categories));
SELECT setval(pg_get_serial_sequence('products', 'id'), (SELECT MAX(id) FROM products));
SELECT setval(pg_get_serial_sequence('product_variants', 'id'), (SELECT MAX(id) FROM product_variants));
SELECT setval(pg_get_serial_sequence('warehouses', 'id'), (SELECT MAX(id) FROM warehouses));
SELECT setval(pg_get_serial_sequence('inventory', 'id'), (SELECT MAX(id) FROM inventory));
SELECT setval(pg_get_serial_sequence('wishlists', 'id'), (SELECT MAX(id) FROM wishlists));
SELECT setval(pg_get_serial_sequence('wishlist_items', 'id'), (SELECT MAX(id) FROM wishlist_items));
SELECT setval(pg_get_serial_sequence('carts', 'id'), (SELECT MAX(id) FROM carts));
SELECT setval(pg_get_serial_sequence('cart_items', 'id'), (SELECT MAX(id) FROM cart_items));
SELECT setval(pg_get_serial_sequence('orders', 'id'), (SELECT MAX(id) FROM orders));
SELECT setval(pg_get_serial_sequence('order_items', 'id'), (SELECT MAX(id) FROM order_items));
SELECT setval(pg_get_serial_sequence('payment_details', 'id'), (SELECT MAX(id) FROM payment_details));

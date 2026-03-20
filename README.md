# 📦 Smart WMS - 工業級智慧倉儲管理系統 (Android)

這是一個基於 **Jetpack Compose** 開發的現代化 Android 倉儲管理系統。
整合了 **機器視覺 (OCR)** 與 **原子性資料庫交易**，解決了傳統入庫點收慢、出庫庫存不一致的痛點。

## 🚀 核心技術亮點 (Technical Highlights)
- **📷 OCR 自動化點收**：整合 Google ML Kit 與 CameraX，即時辨識 SKU 編號。
- **🛡️ 防連掃冷卻系統**：實作 Thread-safe 的 **LRU Cache**，防止高頻掃描導致的數據重複入庫。
- **🔒 原子性交易 (Atomic Transactions)**：使用 Room `@Transaction` 確保多品項出庫時，若任一品項庫存不足則自動回滾，保證數據一致性。
- **⚡ 響應式架構**：採用 MVVM 架構結合 Kotlin Flow，實現資料變更即時反應 UI。

## 應用亮點
-  📷SKU 格式辨識：精準抓取帶有特殊編碼規則的 SKU 字串（如 SKU-TEST-123），適合複雜的商品編號系統。
-  📷全格式條碼支援：涵蓋傳統一維條碼與現代 QR Code，確保新舊標籤皆能無縫讀取。
-  📷純數字 OCR：針對無條碼的生產批號或數量標籤，提供極速的數字提取功能。
-  📷即時資料連動：掃描後立即匹配庫存資訊（如組件名稱、庫存數量），實現「掃描即查詢」。

## 📺 實戰展示 (Demo)
查看掃描器如何快速辨識 SKU 並顯示庫存資訊：
https://youtube.com/shorts/o--0SQ9Pa54

## 🛠️ 技術棧 (Tech Stack)
- **Language:** Kotlin
- **UI:** Jetpack Compose, Material Design 3
- **Database:** Room Persistence Library
- **Vision:** Google ML Kit (Text Recognition), CameraX
- **Concurrency:** Coroutines, StateFlow, SharedFlow


## 🛠️ 如何執行
1. Clone 專案: `git clone https://github.com/你的帳號/WmsApp.git`
2. 使用 Android Studio 開啟。
3. 確保裝置具備相機權限。
4. 點擊 Run 即可執行。

![](src/jvmMain/resources/drawables/qq-mht2html.png)

# QQ MHT2HTML

用于转换QQ导出的MHT文件到单独的html和图片文件。采用Compose Desktop作为GUI库。

谨此纪念许多被炸的老群。

## 功能

* 多线程转换图片, 较快 (因有大量随机读写强烈建议用SSD作为输入输出目录)
* 支持多群组/联系人导出的mht文件, 根据不同群组/联系人拆分文件
* 时间戳转换, 方便搜索(ISO格式)
* 样式压缩
* 支持自定义分页行数, 默认7500行

## 缺点/待改进项

* 基于JVM, 比较吃内存

## 测试数据

环境: Desktop R5-3500X@<!-- -->Fixed4.3GHz(6C6T)/32G@<!-- -->3200MHz/Kioxia CD6 SSD, Win10 21H2, Windows Defender 主动防护关

输入: 27G, 内含70000+张图片, 约360000行聊天记录

耗时: 总耗时~110s

-------------------------

环境: Desktop i9 12900K/32G@<!-- -->6000MHz/WD SN850 SSD, Win11 21H2, ESET 主动防护关

输入: 同上

耗时: 总耗时~60s

-------------------

环境: Desktop R9 7950X/64G@<!-- -->4800MHz/Samsung 980pro/PM983 SSD, Win10 21H2, Windows Defender 主动防护关

输入: 同上

耗时: 总耗时~45s

-------------------
环境: Desktop R9 7950X/64G@<!-- -->4800MHz/Samsung 980pro/PM983 SSD, Win10 21H2, Windows Defender 主动防护开

输入: 同上

耗时: 总耗时~80s

-------------------

环境: Desktop R5-3500X@<!-- -->Fixed4.3GHz(6C6T)/32G@<!-- -->3200MHz/Kioxia CD6 SSD, Win10 21H2, Windows Defender 主动防护关

输入: 50G, 内含100000+张图片, 约800000行聊天记录

耗时: 总耗时~150s

--------

环境: 同上, Windows Defender 主动防护**开**

输入: 同上

耗时: 总耗时~210s

--------

环境: Desktop i5-9400F(6C6T)/16G@<!-- -->2666MHz/Samsung 980pro SSD, Win11, Windows Defender 主动防护关

输入: 同上

耗时: 总耗时~230s

-----------

环境: Laptop Tongfang CODE-01 R7-4800H@<!-- -->54W(Max)(8C16T)/32G@<!-- -->2400MHz/Phison E13T SSD, Win10 21H2, Windows Defender 主动防护关

输入: 同上

耗时: 总耗时~270s

----------

环境: Laptop Dell Latitude 5401 i7-9850H(6C12T)/24G@<!-- -->2400MHz/WD SN520 SSD, Win10 21H2, Windows Defender 主动防护关

输入: 同上

耗时: 总耗时~230s

----------

环境: Desktop Dell Optiplex 3060MFF Celeron-G3930T@<!-- -->Fixed2.7G(2C2T)/16G@<!-- -->2133MHz/Samsung SM961 SSD, Win10 1809, Windows Defender 主动防护关

输入: 同上

耗时: 总耗时~680s

## 参考

* https://github.com/a645162/QQChatRecordMhtToHtmlTool (已经删除/私有。其中一个Fork: https://github.com/bushrose/QQChatRecordMhtToHtmlTool)
* 图标: https://www.pixiv.net/artworks/91517955

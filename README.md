<div align="center">
<img src="src/jvmMain/resources/drawables/qq-mht2html.png" weight="384x" height="384px" />
</div>

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
<details>

<summary>v1.3.0 及以上版本能更好利用多核心, 相较以往版本有15%以上的性能提升</summary>
<br/><br/>
环境: Desktop R5-3500X@<!-- -->Fixed4.3GHz(6C6T)/32G@<!-- -->3200MHz/Kioxia CD6 SSD, Win10 21H2, Windows Defender 主动防护关

输入: 183GB, 内含45万张图片, 约600万行聊天记录

耗时: 总耗时~596s

-------------------------

环境: Laptop Dell Precision 3581 i7-13800H@<!-- -->50W(14C20T)/32G@<!-- -->4800MHz/Phison E13T SSD(R)+WD SN740 SSD(W), Win10 22H2, Windows Defender 主动防护关

输入: 同上

耗时: 总耗时~326s

-------------------------
<br/><br/>
</details>

<details>
<summary>v1.2.x 版本测试数据</summary>
<br/><br/>
环境: Desktop R5-3500X@<!-- -->Fixed4.3GHz(6C6T)/32G@<!-- -->3200MHz/Kioxia CD6 SSD, Win10 21H2, Windows Defender 主动防护关

输入: 183GB, 内含45万张图片, 约600万行聊天记录

耗时: 总耗时~740s

-------------------------
环境: Laptop Dell Precision 3581 i7-13800H@<!-- -->50W(14C20T)/32G@<!-- -->4800MHz/Phison E13T SSD(R)+WD SN740 SSD(W), Win10 22H2, Windows Defender 主动防护关

输入: 同上

耗时: 总耗时~506s

-------------------------

环境: 同上

输入: 27GB, 内含7万张图片, 约36万行聊天记录

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
环境: 同上, Windows Defender 主动防护**开**

输入: 同上

耗时: 总耗时~80s

-------------------

环境: Desktop R5-3500X@<!-- -->Fixed4.3GHz(6C6T)/32G@<!-- -->3200MHz/Kioxia CD6 SSD, Win10 21H2, Windows Defender 主动防护关

输入: 50GB, 内含10万张图片, 约80万行聊天记录

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

环境: Micron 3400 SSD,  Windows Defender 主动防护**开**, 其余同上

输入: 同上

耗时: 总耗时~240s

----------

环境: 同上, Windows Defender 主动防护**关** 

输入: 同上

耗时: 总耗时~210s

----------

环境: Laptop Dell Latitude 5401 i7-9850H(6C12T)/24G@<!-- -->2400MHz/WD SN520 SSD, Win10 21H2, Windows Defender 主动防护关

输入: 同上

耗时: 总耗时~230s

----------

环境: Desktop Dell Optiplex 3060MFF Celeron-G3930T@<!-- -->Fixed2.7G(2C2T)/16G@<!-- -->2133MHz/Samsung SM961 SSD, Win10 1809, Windows Defender 主动防护关

输入: 同上

耗时: 总耗时~680s
</details>

## 参考

* https://github.com/a645162/QQChatRecordMhtToHtmlTool (已经删除/私有。其中一个Fork: https://github.com/bushrose/QQChatRecordMhtToHtmlTool)
* 图标: https://www.pixiv.net/artworks/91517955

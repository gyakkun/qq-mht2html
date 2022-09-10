![](src/jvmMain/resources/drawables/qq-mht2html.png)

# QQ MHT2HTML

用于转换QQ导出的MHT文件到单独的html和图片文件。采用Compose Desktop作为GUI库。

谨此纪念许多被炸的老群。

## 功能

* 多线程转换图片, 较快 (因有大量随机读写强烈建议用SSD作为输入输出目录)
* 支持多群组/联系人导出的mht文件, 根据不同群组/联系人拆分文件
* 时间戳转换, 方便搜索(ISO格式)
* 样式压缩

## 缺点/待改进项

* 尚未支持自定义行数, 默认7500行分页
* 基于JVM, 比较吃内存

## 测试数据

环境: R5 3500X/32G/SM961 SSD

输入: 50G, 内含100000+张图片, 约800000行聊天记录

耗时: 总耗时~170s

--------

环境: i5 9400F/16G/980pro SSD

输入: 同上

耗时: 总耗时~?s

## 参考

* https://github.com/a645162/QQChatRecordMhtToHtmlTool (已经删除/私有。其中一个Fork: https://github.com/bushrose/QQChatRecordMhtToHtmlTool)
* 图标: https://www.pixiv.net/artworks/91517955
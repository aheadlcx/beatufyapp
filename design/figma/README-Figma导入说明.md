# 轻卡 · 卡路里记录 —— Figma 导入说明

`design/figma/` 目录下是 8 张屏幕的 **SVG 矢量稿**，由 `calorie-ios-ui.html` 设计稿自动转换生成，
可直接导入 Figma 成为可编辑图层。

## 文件清单（画布 390px 宽）

| 文件 | 屏幕 | 画布高度 |
|------|------|---------|
| 01-today-overview.svg | 今日总览 | 1174 |
| 02-quick-add.svg | 记一笔 · 快速记录 | 844 |
| 03-food-search.svg | 食物搜索 | 844 |
| 04-food-detail.svg | 食物详情 · 份量 | 844 |
| 05-stats-calorie-week.svg | 统计 · 热量（周） | 912 |
| 06-stats-nutrition-week.svg | 统计 · 营养（周） | 1160 |
| 07-exercise.svg | 运动 | 844 |
| 08-profile.svg | 我的 | 1010 |

## 方式一：直接拖入 SVG（推荐，无需插件）

1. 打开 Figma，进入目标文件（Desktop 或浏览器版均可）。
2. 把 8 个 `.svg` 文件一次性拖入画布（或 `Ctrl+Shift+K` 导入）。
3. 导入后每张图是一个 Frame，内部为图层树：
   - 图层组带中文名（状态栏 / 导航栏 / 内容区 / 早餐 · 07:42 / 标签栏 …），与设计稿分区一致；
   - 文字为**文本图层**（可编辑文字、字号、颜色），字体为 PingFang SC，缺失时批量切换字体即可；
   - 图标 / 环形图 / 柱状图为矢量路径，可拆分、改色；
   - 矩形卡片 / 分割线为独立图层，可调整圆角与填充。
4. 建议：全选后按 1:1 尺寸排列（宽 390），与 HTML 稿数值完全对应。

## 方式二：html.to.design 插件（像素级还原 HTML）

若希望得到更接近原始 HTML 结构的图层（自动间距、组件命名）：

1. Figma 内 `Resources → Plugins` 搜索并运行 **html.to.design**。
2. 选择「Import by URL / HTML」，输入本地起的预览地址（如 `http://127.0.0.1:8138/calorie-ios-ui.html`，
   需先在 `design/` 目录运行 `python save_server.py` 或 `python -m http.server`），
   或直接粘贴 `calorie-ios-ui.html` 源码。
3. 插件会把每屏还原成带自动布局的图层组。

## 方式三：figma-use CLI（AI / 命令行直接写入 Figma 文档）

> 前提：本机安装 Figma 桌面版并已登录，打开目标文件。CLI 已全局安装（v0.13.5，`npm i -g figma-use`）。

```bash
# 1) 连接（Figma 126+ 屏蔽了调试端口，用管道模式由 daemon 拉起 Figma）：
figma-use daemon start --pipe
#    旧版 Figma 也可以："%LOCALAPPDATA%\Figma\Figma.exe" --remote-debugging-port=9222
#    或尝试自动打补丁：figma-use patch
figma-use status          # 显示 "✓ Connected" 即成功

# 2) 把 8 屏矢量稿直接写入当前打开的 Figma 文件：
figma-use import 01-today-overview.svg --x 0    --y 0
figma-use import 02-quick-add.svg        --x 460 --y 0
#   （其余 6 个文件同理，具体参数见 figma-use import --help）

# 3) 需要更细的控制时，可在 Figma 插件上下文执行任意 JS（完整 Plugin API）：
figma-use eval "figma.createPage().name = '轻卡 UI'"
```

说明：figma-use 通过 Chrome DevTools 协议驱动正在运行的 Figma 桌面版，等效于在 Figma 内部
执行 Plugin API（读写均可）；调试通道只在本机进程间使用，用完可 `figma-use daemon stop` 关闭。
官方途径中没有可写文件的 CLI —— REST API 与 Dev Mode MCP 均为只读，`.fig` 二进制格式未公开，
任何工具都无法在 Figma 之外直接生成 `.fig` 文件。

## 设计规范速查（配置 Figma 样式用）

- 颜色：页面底 `#F7F7F7` / 卡片 `#FFFFFF` / 主文字 `#171717` / 辅文字 `#8A8A8A` / 分割线 `#EEEEEE`
- 语义色：摄入 `#F28B45` / 运动·健康 `#29B873` / 超标 `#E65D5D` / 辅助蓝 `#5B8DEF`
- 尺寸：卡片圆角 12 / 列表行高 54 / 导航栏 44 / 图标 26（线性 1.7 描边）
- 字号：核心数字 29–42 / 导航标题 17 / 列表主文 15 / 辅助 12 / 单位 10（kcal 一律小号灰字）

## 说明

Figma 的 `.fig` 是专有二进制格式，官方未开放生成接口，无法在 Figma 之外直接产出 `.fig` 文件；
SVG 导入是标准的无损矢量路径，导入后即为原生 Figma 图层。

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 RSDP 产品导入标准模板（Excel AI 导入链路用）。

输出：web/public/templates/产品导入标准模板.xlsx
包含三个工作表：产品数据（示例行）、填写说明、字典参考。
重新生成：python scripts/generate_product_import_template.py
"""
from openpyxl import Workbook
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter
from openpyxl.worksheet.datavalidation import DataValidation

# ---- 品牌色（与 RSDP 暖调家居馆一致） ----
COLOR_HEADER = "33544D"      # 深松绿（表头）
COLOR_ACCENT = "C9A063"      # 木金（强调）
COLOR_EXAMPLE = "FBF6EE"     # 示例行浅米
COLOR_NOTE = "8C7E6A"        # 辅助文字
COLOR_BAND = "F4F0E8"        # 说明表隔行

thin = Side(style="thin", color="D8D0C4")
BORDER = Border(left=thin, right=thin, top=thin, bottom=thin)

HEADERS = [
    ("型号", 14, "工厂内部型号/货号；同一产品的多个规格模块行填同一型号", True),
    ("品名", 18, "产品名称", True),
    ("品类", 10, "品类码或中文名（FS 座椅/SF 沙发/TB 茶几/FC 柜类/BS 吧椅/OF 办公家具/DT 餐桌/BD 床/LT 灯具）", True),
    ("风格", 12, "风格码或中文名（如 MC 中古风/IT 意式/NC 新中式，见「字典参考」）", False),
    ("主色", 10, "产品主色（如 焦糖棕/米白/黑）", False),
    ("规格/模块", 14, "变体形态名（长几/方几/黑金钻款/贵妃位），同型号不同形态时填", False),
    ("材质", 14, "材质说明（如 实木框架+布艺、头层牛皮），多个用逗号分隔", False),
    ("产品尺寸", 16, "宽*深*高，如 800*900*1000mm；多规格用换行或斜杠分隔", False),
    ("出厂价-A级布", 12, "该材质版本出厂价（数字）。一列一个材质版本，列名格式「出厂价-材质名」，按需增删列", True),
    ("出厂价-半皮", 12, "同上，另一个材质版本的价格列（示例，可删改）", False),
    ("销售价", 10, "零售参考价/含税价（可选，不生成工厂报价）", False),
    ("交期(天)", 9, "生产交期天数（数字）", False),
    ("保修年限", 9, "保修年数（数字）", False),
    ("产品等级", 9, "S/A/B/C（工厂能力等级）", False),
    ("场景", 12, "适用空间，多个用逗号分隔（如 客厅,书房）", False),
    ("描述/配置说明", 28, "长文本描述、功能配置、材质解析等，原样保留", False),
    ("主图URL", 20, "产品主图链接；也可直接把图片粘进本列单元格（内嵌图自动识别）", False),
    ("详情图URLs", 20, "详情图链接，多个用逗号分隔", False),
    ("数量", 7, "件数（数字，可选）", False),
]

EXAMPLES = [
    ["MJ-S96", "云屿沙发", "SF", "MC", "焦糖棕", "", "实木框架,布艺", "2200*950*850mm",
     2680, 3280, 5980, 25, 3, "A", "客厅,书房", "三人位，鹅绒靠包，可拆洗", "https://example.com/img/mj-s96-1.jpg", "", 1],
    ["MJ-S96", "云屿沙发", "", "", "", "贵妃位", "实木框架,布艺", "1600*950*850mm",
     1880, 2280, "", "", "", "", "", "贵妃右位，与三人位同面料", "", "", 1],
    ["A-102", "温莎椅", "FS", "WJ", "原木色", "", "白蜡木", "450*500*820mm",
     460, "", 980, 15, 2, "B", "餐厅,书房", "经典温莎椅，榫卯结构", "https://example.com/img/a-102.jpg", "https://example.com/img/a-102-d1.jpg", 4],
]

CATEGORIES = [
    ("FS", "座椅（椅子/凳子/餐椅/休闲椅）"), ("SF", "沙发"), ("TB", "茶几"), ("FC", "柜类"),
    ("BS", "吧椅"), ("OF", "办公家具"), ("DT", "餐桌"), ("BD", "床"), ("LT", "灯具"),
]
STYLES = [
    ("MC", "中古风"), ("BA", "包豪斯"), ("IT", "意式"), ("FR", "法式"), ("WJ", "侘寂"),
    ("NC", "新中式"), ("CR", "奶油风"), ("IN", "工业风"), ("MP", "孟菲斯"), ("IL", "意式极简轻奢"),
    ("ZS", "新中式宋式"), ("MB", "现代极简包豪斯"), ("MD", "孟菲斯多巴胺"), ("IO", "工业风LOFT"),
    ("FN", "法式复古南洋"), ("HH", "混搭风"), ("DL", "国外顶尖大牌搭配"), ("TBD", "待识别（不确定时留空即可）"),
]
LEVELS = [("S", "豪华"), ("A", "高端"), ("B", "中端"), ("C", "经济型")]


def style_header(ws, ncols, row=1, height=32):
    ws.row_dimensions[row].height = height
    for col in range(1, ncols + 1):
        cell = ws.cell(row=row, column=col)
        cell.font = Font(name="微软雅黑", size=10, bold=True, color="FFFFFF")
        cell.fill = PatternFill("solid", fgColor=COLOR_HEADER)
        cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)
        cell.border = BORDER


def build_data_sheet(wb):
    ws = wb.active
    ws.title = "产品数据"
    ws.sheet_view.showGridLines = False

    # 表头
    for idx, (name, width, _, required) in enumerate(HEADERS, start=1):
        ws.cell(row=1, column=idx, value=name)
        ws.column_dimensions[get_column_letter(idx)].width = width
    style_header(ws, len(HEADERS))
    ws.freeze_panes = "A2"

    # 示例行（浅米色底，导入前删除）
    for r, row in enumerate(EXAMPLES, start=2):
        ws.row_dimensions[r].height = 22
        for c, value in enumerate(row, start=1):
            cell = ws.cell(row=r, column=c, value=value)
            cell.font = Font(name="微软雅黑", size=9, color="6B6157")
            cell.fill = PatternFill("solid", fgColor=COLOR_EXAMPLE)
            cell.alignment = Alignment(vertical="center", wrap_text=True)
            cell.border = BORDER
    # 示例行标注
    note = ws.cell(row=len(EXAMPLES) + 2, column=1,
                   value="▲ 以上 3 行为填写示例（含「同型号多规格模块」写法），正式导入前请删除")
    note.font = Font(name="微软雅黑", size=9, italic=True, color=COLOR_NOTE)

    # 数据区默认字体/边框引导（预留 200 行浅边框，方便用户感知填写区）
    for r in range(len(EXAMPLES) + 3, len(EXAMPLES) + 23):
        for c in range(1, len(HEADERS) + 1):
            ws.cell(row=r, column=c).border = BORDER

    # 品类下拉（引导但不强制，避免挡住中文名写法）
    dv = DataValidation(type="list", formula1='"FS,SF,TB,FC,BS,OF,DT,BD,LT"', allow_blank=True,
                        showErrorMessage=False)
    ws.add_data_validation(dv)
    dv.add(f"C2:C500")
    return ws


def build_readme_sheet(wb):
    ws = wb.create_sheet("填写说明")
    ws.sheet_view.showGridLines = False
    ws.column_dimensions["A"].width = 3
    ws.column_dimensions["B"].width = 16
    ws.column_dimensions["C"].width = 8
    ws.column_dimensions["D"].width = 72

    title = ws.cell(row=1, column=2, value="RSDP 产品导入标准模板 · 填写说明")
    title.font = Font(name="微软雅黑", size=14, bold=True, color=COLOR_HEADER)
    ws.row_dimensions[1].height = 30

    rules = [
        "1. 【铁律】同一型号的所有行，「型号」「品名」必须一字不差。颜色/材质/配置/档位/形态等变体差异，一律不许写进品名——否则触发「同型号多个商品名称」拦截",
        "2. 变体差异的正确归宿：颜色外观（黑钛/星空黑/宝格丽）→「主色」列；材质面板（岩板/超晶石/A级布）→ 按材质拆价格列；配置档位（标配/高配/含不含消毒柜）→「描述/配置说明」列；形态规格（长几/方几/贵妃位）→「规格/模块」列",
        "3. 一行 = 一个变体（型号×规格×材质的组合），该行只填自己的差异值；同一产品的模块行（组合件），品名/品类可留空自动继承上一行",
        "4. 出厂价按材质版本一列一价，列名格式「出厂价-材质名」（如 出厂价-A级布、出厂价-岩板），可按需增删价格列；出厂价会生成工厂报价（RSKU），导入时需选择归属工厂",
        "5. 销售价只作为产品零售参考价记录，不生成工厂报价",
        "6. 图片两种方式任选：① 主图URL 列填图片链接；② 直接把图片粘贴进「主图URL」列的单元格（内嵌图片自动按行提取，每行第一张为主图）。注意：必须关闭表格软件的图片压缩再插入原图，宽或高不足 200px 的图不能升主图",
        "7. 品类/风格/材质/颜色可填中文名，系统自动归一到字典；风格不确定可留空，AI 看图识别后会补",
        "8. 单次导入不超过 500 行；带 * 的列为必填（型号、品名、品类、至少一个出厂价列）",
        "9. 首次导入确认字段映射时，可勾选「保存为映射模板」——之后同结构表格免 AI 识别直接导入",
    ]
    r = 3
    for rule in rules:
        cell = ws.cell(row=r, column=2, value=rule)
        cell.font = Font(name="微软雅黑", size=10, color="4A4238")
        cell.alignment = Alignment(wrap_text=True, vertical="center")
        ws.merge_cells(start_row=r, start_column=2, end_row=r, end_column=4)
        ws.row_dimensions[r].height = 30 if len(rule) > 40 else 20
        r += 1

    r += 1
    head = ws.cell(row=r, column=2, value="列字段速查")
    head.font = Font(name="微软雅黑", size=11, bold=True, color=COLOR_HEADER)
    r += 1
    for col, text in zip("BCD", ["列名", "必填", "说明"]):
        c = ws.cell(row=r, column=ord(col) - 64, value=text)
        c.font = Font(name="微软雅黑", size=10, bold=True, color="FFFFFF")
        c.fill = PatternFill("solid", fgColor=COLOR_HEADER)
        c.alignment = Alignment(horizontal="center")
        c.border = BORDER
    r += 1
    for i, (name, _, desc, required) in enumerate(HEADERS):
        band = PatternFill("solid", fgColor=COLOR_BAND) if i % 2 == 0 else None
        for col, value in ((2, name), (3, "必填" if required else ""), (4, desc)):
            cell = ws.cell(row=r, column=col, value=value)
            cell.font = Font(name="微软雅黑", size=9)
            cell.border = BORDER
            cell.alignment = Alignment(vertical="center", wrap_text=True)
            if band:
                cell.fill = band
        ws.row_dimensions[r].height = 26
        r += 1
    return ws


def build_dict_sheet(wb):
    ws = wb.create_sheet("字典参考")
    ws.sheet_view.showGridLines = False
    for col, width in zip("ABCDE", (3, 12, 30, 12, 30)):
        ws.column_dimensions[col].width = width

    def block(title, rows, start_row, code_col, name_col):
        t = ws.cell(row=start_row, column=code_col, value=title)
        t.font = Font(name="微软雅黑", size=11, bold=True, color=COLOR_HEADER)
        r = start_row + 1
        for col, text in ((code_col, "编码"), (name_col, "名称")):
            c = ws.cell(row=r, column=col, value=text)
            c.font = Font(name="微软雅黑", size=10, bold=True, color="FFFFFF")
            c.fill = PatternFill("solid", fgColor=COLOR_HEADER)
            c.alignment = Alignment(horizontal="center")
            c.border = BORDER
        r += 1
        for i, (code, name) in enumerate(rows):
            for col, value in ((code_col, code), (name_col, name)):
                cell = ws.cell(row=r, column=col, value=value)
                cell.font = Font(name="微软雅黑", size=9)
                cell.border = BORDER
                if i % 2 == 0:
                    cell.fill = PatternFill("solid", fgColor=COLOR_BAND)
            r += 1
        return r + 1

    next_r = block("品类码（category）", CATEGORIES, 2, 2, 3)
    block("产品等级（factory_level）", LEVELS, next_r, 2, 3)
    block("风格码（style）", STYLES, 2, 4, 5)
    return ws


def main():
    import os
    wb = Workbook()
    build_data_sheet(wb)
    build_readme_sheet(wb)
    build_dict_sheet(wb)
    out = "web/public/templates/产品导入标准模板.xlsx"
    os.makedirs(os.path.dirname(out), exist_ok=True)
    wb.save(out)
    print("written:", out)


if __name__ == "__main__":
    main()

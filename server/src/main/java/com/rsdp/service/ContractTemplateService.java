package com.rsdp.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 合同模板服务：生成 docx 采购合同模板（占位符供人工填写）。
 */
@Slf4j
@Service
public class ContractTemplateService {

    private static final int TITLE_FONT_SIZE = 18;
    private static final int BODY_FONT_SIZE = 11;

    /**
     * 生成采购合同 docx 模板字节流。
     *
     * @return docx 字节数组
     */
    public byte[] generateContractTemplate() {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            addTitle(doc, "产品采购合同");
            addBody(doc, "合同编号：{{orderNo}}");
            addBody(doc, "签订日期：{{signDate}}");
            addBody(doc, "");
            addBody(doc, "甲方（采购方）：{{buyerName}}");
            addBody(doc, "联系人：{{buyerContact}}    电话：{{buyerPhone}}");
            addBody(doc, "地址：{{buyerAddress}}");
            addBody(doc, "");
            addBody(doc, "乙方（供应方）：{{sellerName}}");
            addBody(doc, "联系人：{{sellerContact}}    电话：{{sellerPhone}}");
            addBody(doc, "");
            addBody(doc, "一、产品明细");
            addItemTable(doc);
            addBody(doc, "");
            addBody(doc, "合同总金额（到手价）：人民币 {{totalPrice}} 元");
            addBody(doc, "预计交期：{{expectedLeadTime}} 天");
            addBody(doc, "");
            addBody(doc, "二、付款方式");
            addBody(doc, "{{paymentTerms}}");
            addBody(doc, "");
            addBody(doc, "三、交付与验收");
            addBody(doc, "1. 交付地点：{{deliveryAddress}}");
            addBody(doc, "2. 验收标准：按双方确认的产品图纸及样品验收。");
            addBody(doc, "");
            addBody(doc, "四、违约责任");
            addBody(doc, "{{breachTerms}}");
            addBody(doc, "");
            addBody(doc, "五、其他约定");
            addBody(doc, "1. 本合同一式两份，甲乙双方各执一份，自双方签字（盖章）之日起生效。");
            addBody(doc, "2. 未尽事宜由双方协商解决。");
            addBody(doc, "");
            addBody(doc, "甲方（签字/盖章）：________________    乙方（签字/盖章）：________________");
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("合同模板生成失败", e);
        }
    }

    private void addTitle(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(TITLE_FONT_SIZE);
        run.setFontFamily("宋体");
    }

    private void addBody(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setFontSize(BODY_FONT_SIZE);
        run.setFontFamily("宋体");
    }

    private void addItemTable(XWPFDocument doc) {
        String[] headers = {"序号", "产品名称", "型号", "数量", "到手单价（元）", "小计（元）"};
        XWPFTable table = doc.createTable(2, headers.length);
        XWPFTableRow headerRow = table.getRow(0);
        for (int i = 0; i < headers.length; i++) {
            headerRow.getCell(i).setText(headers[i]);
        }
        XWPFTableRow sampleRow = table.getRow(1);
        String[] sample = {"1", "{{productName}}", "{{model}}", "{{quantity}}", "{{finalPrice}}", "{{subtotal}}"};
        for (int i = 0; i < sample.length; i++) {
            sampleRow.getCell(i).setText(sample[i]);
        }
    }
}

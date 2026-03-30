package org.javaup.enums;

/**
 * 文档文件类型枚举。
 */
public enum DocumentFileTypeEnum {
    PDF(1, "PDF"),
    DOC(2, "DOC"),
    DOCX(3, "DOCX"),
    TXT(4, "TXT"),
    MD(5, "MD"),
    HTML(6, "HTML");

    private final Integer code;

    private final String msg;

    DocumentFileTypeEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public Integer getCode() {
        return code;
    }

    public String getMsg() {
        return msg == null ? "" : msg;
    }

    /**
     * 根据 code 获取枚举。
     */
    public static DocumentFileTypeEnum getRc(Integer code) {
        for (DocumentFileTypeEnum item : DocumentFileTypeEnum.values()) {
            if (item.code.intValue() == code.intValue()) {
                return item;
            }
        }
        return null;
    }

    /**
     * 根据文件名后缀识别类型。
     */
    public static DocumentFileTypeEnum fromFileName(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return null;
        }
        String suffix = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        return switch (suffix) {
            case "pdf" -> PDF;
            case "doc" -> DOC;
            case "docx" -> DOCX;
            case "txt" -> TXT;
            case "md", "markdown" -> MD;
            case "html", "htm" -> HTML;
            default -> null;
        };
    }
}

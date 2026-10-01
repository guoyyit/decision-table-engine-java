package com.github.guoyyit.decisiontableenginejava.eval;

/**
 * 评估过程中可定位到具体规则/列的异常。
 */
public class EvaluationException extends RuntimeException {
    private final String tableId;
    private final String rowId;
    private final int columnIndex;

    public EvaluationException(String tableId, String rowId, int columnIndex, String message) {
        super(message);
        this.tableId = tableId;
        this.rowId = rowId;
        this.columnIndex = columnIndex;
    }

    public String tableId() { return tableId; }
    public String rowId() { return rowId; }
    public int columnIndex() { return columnIndex; }
}

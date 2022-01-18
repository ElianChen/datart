package datart.data.provider.script;

import datart.core.base.consts.Const;
import datart.core.base.consts.VariableTypeEnum;
import datart.core.base.exception.Exceptions;
import datart.core.data.provider.ScriptVariable;
import datart.data.provider.base.DataProviderException;
import datart.data.provider.calcite.SqlFunctionRegisterVisitor;
import datart.data.provider.calcite.SqlNodeUtils;
import datart.data.provider.calcite.SqlValidateUtils;
import datart.data.provider.calcite.custom.SqlSimpleStringLiteral;
import datart.data.provider.jdbc.SqlScriptRender;
import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.fun.SqlLikeOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.commons.collections4.CollectionUtils;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class VariablePlaceholder {

    protected final List<ScriptVariable> variables;

    protected final SqlDialect sqlDialect;

    protected final SqlCall sqlCall;

    protected final String originalSqlFragment;

    public ReplacementPair replacementPair() {

        if (CollectionUtils.isEmpty(variables)) {
            return replacePermissionVariable(variables);
        }

        return variables.stream().allMatch(variable -> VariableTypeEnum.PERMISSION.equals(variable.getType())) ?
                replacePermissionVariable(variables)
                : replaceQueryVariable(variables);
    }

    public VariablePlaceholder(List<ScriptVariable> variables, SqlDialect sqlDialect, SqlCall sqlCall, String originalSqlFragment) {
        this.variables = variables;
        this.sqlDialect = sqlDialect;
        this.sqlCall = sqlCall;
        this.originalSqlFragment = originalSqlFragment;
    }


    protected SqlCall autoFixSqlCall(ScriptVariable variable) {
        //SqlNode to build a new SqlCall
        SqlOperator sqlOperator = sqlCall.getOperator();
        List<SqlNode> operandList = new ArrayList<>();

        SqlKind kind = sqlCall.getOperator().kind;

        switch (kind) {
            case GREATER_THAN:
            case GREATER_THAN_OR_EQUAL:
                reduceVariableToMin(variable);
                replaceVariable(sqlCall, variable);
                operandList.addAll(sqlCall.getOperandList());
                break;
            case LESS_THAN:
            case LESS_THAN_OR_EQUAL:
                reduceVariableToMax(variable);
                replaceVariable(sqlCall, variable);
                operandList.addAll(sqlCall.getOperandList());
                break;
            case EQUALS:
                sqlOperator = SqlStdOperatorTable.IN;
                replaceVariable(sqlCall, variable);
                operandList.addAll(sqlCall.getOperandList());
                break;
            case NOT_EQUALS:
                sqlOperator = SqlStdOperatorTable.NOT_IN;
                replaceVariable(sqlCall, variable);
                operandList.addAll(sqlCall.getOperandList());
                break;
            case LIKE:
                SqlLikeOperator likeOperator = (SqlLikeOperator) sqlCall.getOperator();
                if (likeOperator.isNegated()) {
                    sqlOperator = SqlStdOperatorTable.AND;
                    operandList = variable.getValues().stream().map(val -> {
                        ArrayList<SqlNode> operands = new ArrayList<>();
                        operands.add(sqlCall.getOperandList().get(0));
                        operands.add(new SqlSimpleStringLiteral(val));
                        return SqlNodeUtils
                                .createSqlBasicCall(SqlStdOperatorTable.NOT_LIKE, operands);
                    }).collect(Collectors.toList());
                } else {
                    sqlOperator = SqlStdOperatorTable.OR;

                    operandList = variable.getValues().stream().map(val -> {
                        ArrayList<SqlNode> operands = new ArrayList<>();
                        operands.add(sqlCall.getOperandList().get(0));
                        operands.add(new SqlSimpleStringLiteral(val));
                        return SqlNodeUtils
                                .createSqlBasicCall(SqlStdOperatorTable.LIKE, operands);
                    }).collect(Collectors.toList());
                }
                break;
            default:
                replaceVariable(sqlCall, variable);
                operandList.addAll(sqlCall.getOperandList());
                break;
        }
        return SqlNodeUtils.createSqlBasicCall(sqlOperator, operandList);
    }

    protected void reduceVariableToMin(ScriptVariable variable) {
        String minVal;
        switch (variable.getValueType()) {
            case DATE:
            case STRING:
                minVal = variable.getValues().stream().map(Object::toString).min(String::compareTo).get();
                break;
            case NUMERIC:
                minVal = variable.getValues().stream().mapToDouble(v -> Double.parseDouble(v.toString())).min().getAsDouble() + "";
                break;
            default:
                minVal = variable.getValues().toArray()[0].toString();
        }
        variable.getValues().clear();
        variable.getValues().add(minVal);
    }

    protected void reduceVariableToMax(ScriptVariable variable) {
        String maxVal;
        switch (variable.getValueType()) {
            case DATE:
            case STRING:
                maxVal = variable.getValues().stream().map(Object::toString).max(String::compareTo).get();
                break;
            case NUMERIC:
                maxVal = variable.getValues().stream().mapToDouble(v -> Double.parseDouble(v.toString())).max().getAsDouble() + "";
                break;
            default:
                maxVal = variable.getValues().toArray()[0].toString();
        }
        variable.getValues().clear();
        variable.getValues().add(maxVal);
    }

    protected SqlCall createIsNullSqlCall(SqlNode sqlNode) {
        return new SqlBasicCall(SqlStdOperatorTable.IS_NULL, new SqlNode[]{sqlNode}, sqlNode.getParserPosition());
    }

    protected void replaceVariable(SqlCall sqlCall, ScriptVariable variable) {
        // register function for sql output
        if (sqlCall.getOperator() instanceof SqlFunction) {
            new SqlFunctionRegisterVisitor().visit(sqlCall);
        }

        for (int i = 0; i < sqlCall.operandCount(); i++) {
            SqlNode sqlNode = sqlCall.getOperandList().get(i);
            if (sqlNode == null) {
                continue;
            }
            if (sqlNode instanceof SqlCall) {
                replaceVariable((SqlCall) sqlNode, variable);
            } else if (sqlNode instanceof SqlLiteral) {
                // pass
            } else if (sqlNode instanceof SqlIdentifier) {
                if (sqlNode.toString().equalsIgnoreCase(variable.getNameWithQuote())) {
                    sqlCall.setOperand(i, SqlNodeUtils.toSingleSqlLiteral(variable, sqlNode.getParserPosition()));
                }
            } else if (sqlNode instanceof SqlNodeList) {
                SqlNodeList nodeList = (SqlNodeList) sqlNode;

                List<SqlNode> toRemove = new LinkedList<>();
                List<SqlNode> toAdd = new LinkedList<>();

                for (SqlNode node : nodeList.getList()) {
                    if (node instanceof SqlCall) {
                        replaceVariable((SqlCall) node, variable);
                    } else {
                        if (node.toString().equalsIgnoreCase(variable.getNameWithQuote())) {
                            List<SqlNode> variableNodes = SqlNodeUtils.createSqlNodes(variable, sqlCall.getParserPosition());
                            if (CollectionUtils.isNotEmpty(variableNodes)) {
                                toAdd.addAll(variableNodes);
                            }
                            toRemove.add(node);
                        }
                    }
                }

                nodeList.getList().removeAll(toRemove);
                nodeList.getList().addAll(toAdd);

//                sqlCall.setOperand(i, nodeList);
            } else {
                Exceptions.tr(DataProviderException.class, "message.provider.sql.variable", sqlNode.toSqlString(sqlDialect).getSql());
            }
        }
    }

    /**
     * 权限变量替换规则：
     * 1、权限变量不存在，替换整个表达式为1=1
     * 2、权限变量存在但值为空，替换整个表达式为1=0
     * 3、一个表达式中有多个变量，直接替换
     * 4、一个表达式中只有一个变量，根据值个数进行替换
     * 5、其它情况，直接替换
     */
    private ReplacementPair replacePermissionVariable(List<ScriptVariable> variables) {

        if (CollectionUtils.isEmpty(variables)) {
            return new ReplacementPair(originalSqlFragment, SqlScriptRender.TRUE_CONDITION);
        }

        if (variables.size() > 1) {
            for (ScriptVariable variable : variables) {
                replaceVariable(sqlCall, variable);
            }
            return new ReplacementPair(originalSqlFragment, SqlNodeUtils.toSql(sqlCall, sqlDialect));
        }

        ScriptVariable variable = variables.get(0);
        if (CollectionUtils.isEmpty(variable.getValues())) {
            return new ReplacementPair(originalSqlFragment, SqlScriptRender.FALSE_CONDITION);
        }

        for (Serializable value : variable.getValues()) {
            if (Const.ALL_PERMISSION.equals(value.toString())) {
                return new ReplacementPair(originalSqlFragment, SqlScriptRender.TRUE_CONDITION);
            }
        }

        if (variable.getValues().size() == 1) {
            replaceVariable(sqlCall, variable);
            return new ReplacementPair(originalSqlFragment, SqlNodeUtils.toSql(sqlCall, sqlDialect));
        }

        SqlCall fixSqlCall = autoFixSqlCall(variable);
        return new ReplacementPair(originalSqlFragment, SqlNodeUtils.toSql(fixSqlCall, sqlDialect));
    }

    /**
     * 查询变量替换规则：
     * 1、变量不存在或者变量值为空，直接返回
     * 2、一个表达式中有多个不同变量，直接替换
     * 3、一个表达式中只有一个变量，且表达式是一个布尔表达式，根据值的个数进行优化
     * 4、其它情况，直接替换
     */
    private ReplacementPair replaceQueryVariable(List<ScriptVariable> variables) {
        if (CollectionUtils.isEmpty(variables)) {
            return new ReplacementPair(originalSqlFragment, originalSqlFragment);
        }
        if (variables.size() > 1) {
            for (ScriptVariable variable : variables) {
                replaceVariable(sqlCall, variable);
            }
            return new ReplacementPair(originalSqlFragment, SqlNodeUtils.toSql(sqlCall, sqlDialect));
        }
        ScriptVariable variable = variables.get(0);
        if (CollectionUtils.isEmpty(variable.getValues())) {
            log.warn("The query variable [" + variable.getName() + "] do not have default values, which may cause SQL syntax errors");
            SqlCall isNullSqlCall = createIsNullSqlCall(sqlCall.getOperandList().get(0));
            return new ReplacementPair(originalSqlFragment, SqlNodeUtils.toSql(isNullSqlCall, sqlDialect));
        }
        if (variable.getValues().size() > 1 && SqlValidateUtils.isLogicExpressionSqlCall(sqlCall)) {
            SqlCall fixedCall = autoFixSqlCall(variable);
            return new ReplacementPair(originalSqlFragment, SqlNodeUtils.toSql(fixedCall, sqlDialect));
        } else {
            replaceVariable(sqlCall, variable);
            return new ReplacementPair(originalSqlFragment, SqlNodeUtils.toSql(sqlCall, sqlDialect));
        }
    }

    public int getStartPos() {
        return sqlCall.getParserPosition().getColumnNum();
    }

}
package com.bankingax.task.a01;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** MySQL adapter for the synthetic deposit_account/account_transaction tables in the approved DDL. */
@Component
@ConditionalOnProperty(name="app.a01.transaction-query-port", havingValue="jdbc")
public final class JdbcTransactionQueryPort implements TransactionQueryPort {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcTransactionQueryPort(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public Optional<TransactionAccount> findAccount(long accountId){
        var rows=jdbc.query("SELECT id, customer_id, account_no, account_name, is_active FROM deposit_account WHERE id=:id",Map.of("id",accountId),(rs,row)->new TransactionAccount(rs.getLong("id"),rs.getLong("customer_id"),mask(rs.getString("account_no")),rs.getString("account_name"),rs.getBoolean("is_active")));
        return rows.stream().findFirst();
    }
    @Override public TransactionPage search(TransactionQuery query){
        Map<String,Object> values=new HashMap<>(); values.put("accountId",query.accountId()); values.put("start",Timestamp.valueOf(query.startInclusive())); values.put("end",Timestamp.valueOf(query.endExclusive())); values.put("offset",query.offset()); values.put("size",query.size());
        StringBuilder where=new StringBuilder(" WHERE account_id=:accountId AND tx_at>=:start AND tx_at<:end");
        if(query.direction()!=null){where.append(" AND direction=:direction");values.put("direction",query.direction().name());}
        if(query.minimumAmount()!=null){where.append(" AND amount>=:minimumAmount");values.put("minimumAmount",query.minimumAmount());}
        if(query.maximumAmount()!=null){where.append(" AND amount<=:maximumAmount");values.put("maximumAmount",query.maximumAmount());}
        if(query.keyword()!=null){where.append(" AND (counterparty LIKE :keyword OR memo LIKE :keyword)");values.put("keyword","%"+query.keyword()+"%");}
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM account_transaction"+where,values,Long.class);
        String order=query.sortOrder()==TransactionSortOrder.OLDEST?"tx_at ASC, id ASC":"tx_at DESC, id DESC";
        var rows=jdbc.query("SELECT id, account_id, tx_at, direction, amount, counterparty, memo FROM account_transaction"+where+" ORDER BY "+order+" LIMIT :size OFFSET :offset",values,(rs,row)->new TransactionRecord(rs.getLong("id"),rs.getLong("account_id"),rs.getTimestamp("tx_at").toLocalDateTime(),TransactionDirection.valueOf(rs.getString("direction")),rs.getBigDecimal("amount"),rs.getString("counterparty"),rs.getString("memo")));
        return new TransactionPage(rows,total);
    }
    private static String mask(String accountNo){if(accountNo==null||accountNo.length()<5)return "***";return accountNo.substring(0,Math.min(3,accountNo.length()-4))+"-***-"+accountNo.substring(accountNo.length()-4);}
}

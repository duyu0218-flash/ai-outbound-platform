package com.duozhi.outbound;

import java.util.*;
import org.apache.ibatis.session.SqlSession;
import org.springframework.stereotype.Repository;

/** Only fixed, bound MyBatis statements; callers must supply an authenticated tenant. */
@Repository
public class OperationsStore {
    private final SqlSession sql;
    public OperationsStore(SqlSession sql){this.sql=sql;}
    private String statement(String name){return "com.duozhi.outbound.mapper.OperationsMapper."+name;}
    public List<Map<String,Object>> rows(String name,Map<String,Object> p){return sql.selectList(statement(name),p);}
    public Map<String,Object> one(String name,Map<String,Object> p){return sql.selectOne(statement(name),p);}
    public long count(String name,Map<String,Object> p){Number n=sql.selectOne(statement(name),p);return n==null?0:n.longValue();}
    public int write(String name,Map<String,Object> p){return sql.update(statement(name),p);}
}

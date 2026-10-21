package com.duozhi.outbound.embedded;

import java.sql.Connection;
import java.util.*;

/** Exact oracle-equivalent counts over compact durable buckets. Recent CPS
 * still uses the bounded one-second created index, never a lifetime scan. */
final class VoiceScopeFacts {
 static Map<String,Object> read(Connection db,String where,List<Object> args,double now)throws Exception{
  String type=args.isEmpty()?"global":args.size()==1?"tenant":"line";
  long tenant=args.isEmpty()?0:((Number)args.get(0)).longValue();String line=args.size()<2?"":args.get(1).toString();
  double day=Math.floor(now/86400)*86400,hour=Math.floor(now/3600)*3600;
  return LocalLedger.query(db,"SELECT COALESCE(SUM(IF(bucket_kind='active',fact_count,0)),0) AS active,COALESCE(SUM(IF(bucket_kind='created_day' AND bucket_start>=?,fact_count,0)),0) AS daily,COALESCE(SUM(IF(bucket_kind='active' OR (bucket_kind='ended_hour' AND bucket_start>=?),cost,0)),0) AS hour_budget,COALESCE(SUM(IF(bucket_kind='active' OR (bucket_kind='ended_hour' AND bucket_start>=?),cost,0)),0) AS day_budget FROM voice_scope_counter WHERE scope_type=? AND tenant_id=? AND line_id=?",day,hour,day,type,tenant,line).get(0);
 }
}

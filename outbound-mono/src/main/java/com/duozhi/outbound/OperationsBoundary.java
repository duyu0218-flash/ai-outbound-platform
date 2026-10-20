package com.duozhi.outbound;

import java.util.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;

@Configuration
public class OperationsBoundary implements WebMvcConfigurer {
 private final OperationsAccess access;
 public OperationsBoundary(OperationsAccess access){this.access=access;}
 @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(new HandlerInterceptor(){
  @Override public boolean preHandle(HttpServletRequest r,HttpServletResponse response,Object handler){
   if(!(r.getAttribute("identity") instanceof Identity i)||i.user()==null)return true;
   String path=r.getRequestURI();
   if(path.matches("/api/v1/calls/[0-9a-fA-F-]{36}(?:/.*)?")){
    if(!(r.getMethod().equals("POST")&&path.matches(".*/handoffs/[0-9]+/accept")))access.call(i,path.split("/")[4]);
    if(path.endsWith("/recordings")&&!access.flag(i,"can_record"))throw new ApiError(403,"没有录音访问权限");
   }
   if(access.managed(i)&&path.startsWith("/api/v1/")&&!path.startsWith("/api/v1/ops/")&&!path.startsWith("/api/v1/auth/")&&!path.startsWith("/api/v1/calls")&&!path.startsWith("/api/v1/handoffs")&&!path.startsWith("/api/v1/webrtc/")&&!(r.getMethod().equals("GET")&&path.startsWith("/api/v1/knowledge")))throw new ApiError(403,"请使用具有团队数据范围的运营工作台");
   return true;
  }
 }).addPathPatterns("/api/v1/**");}
}

@RestControllerAdvice
class OperationsPhoneMask implements ResponseBodyAdvice<Object> {
 private final OperationsAccess access;
 OperationsPhoneMask(OperationsAccess access){this.access=access;}
 public boolean supports(MethodParameter returnType,Class<? extends HttpMessageConverter<?>> converter){return true;}
 public Object beforeBodyWrite(Object body,MethodParameter type,MediaType media,Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest request,ServerHttpResponse response){
  if(request instanceof ServletServerHttpRequest servlet&&servlet.getServletRequest().getAttribute("identity") instanceof Identity i&&i.user()!=null){Object out=access.flag(i,"can_unmask")?body:mask(body);return access.flag(i,"can_record")?out:withoutRecording(out);}
  return body;
 }
 static Object withoutRecording(Object value){
  if(value instanceof Map<?,?> source){var out=new LinkedHashMap<String,Object>();source.forEach((k,v)->out.put(k.toString(),Set.of("recording_url","recording_urls","recording_storage_uris","storage_uri","provider_url").contains(k.toString())?null:withoutRecording(v)));return out;}
  if(value instanceof List<?> list)return list.stream().map(OperationsPhoneMask::withoutRecording).toList();return value;
 }
 static Object mask(Object value){
  if(value instanceof Map<?,?> source){var out=new LinkedHashMap<String,Object>();source.forEach((k,v)->out.put(k.toString(),Set.of("phone","contact_phone").contains(k.toString())&&v!=null?v.toString().replaceAll("(?<=.{3}).(?=.{4})","*"):mask(v)));return out;}
  if(value instanceof List<?> list)return list.stream().map(OperationsPhoneMask::mask).toList();return value;
 }
}

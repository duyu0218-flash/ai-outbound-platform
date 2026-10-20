package com.duozhi.outbound.embedded;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Same-origin, read-only API reference. No CDN, credentials, or arbitrary requests. */
@RestController
final class ApiDocumentation {
    private final byte[] specification;
    private static final String CSS = "body{font:16px system-ui;margin:2rem;max-width:1000px;color:#182438}input{padding:.6rem;width:90%}article{border:1px solid #ccd5e0;border-radius:8px;padding:1rem;margin:1rem 0}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f4f6fa;padding:1rem}button{padding:.4rem}small{color:#536074}";
    private static final String JS = "fetch('/openapi.json',{credentials:'omit'}).then(r=>{if(!r.ok)throw Error('API document unavailable');return r.json()}).then(s=>{document.getElementById('title').textContent=s.info.title;document.getElementById('description').textContent=s.info.description;const area=document.getElementById('operations');for(const [path,methods] of Object.entries(s.paths)){for(const [method,op] of Object.entries(methods)){if(!['get','post','put','patch','delete'].includes(method))continue;const item=document.createElement('article');item.dataset.search=(method+' '+path+' '+(op.summary||'')).toLowerCase();const title=document.createElement('h2');title.textContent=method.toUpperCase()+' '+path;item.append(title);const text=document.createElement('p');text.textContent=op.description||op.summary||'';item.append(text);const details=document.createElement('details');const summary=document.createElement('summary');summary.textContent='Parameters, request and responses';details.append(summary);const pre=document.createElement('pre');pre.textContent=JSON.stringify(op,null,2);details.append(pre);item.append(details);area.append(item)}}const model=document.getElementById('schemas');model.textContent=JSON.stringify(s.components||{},null,2);document.getElementById('filter').addEventListener('input',e=>{const q=e.target.value.toLowerCase();for(const item of area.children)item.hidden=!item.dataset.search.includes(q)});document.getElementById('status').textContent=area.children.length+' documented operations'}).catch(e=>{document.getElementById('status').textContent=e.message})";
    ApiDocumentation() throws IOException {
        Map<String,Object> root;try(var input=ApiDocumentation.class.getResourceAsStream("/contracts/openapi-baseline.json")){if(input==null)throw new IOException("platform contract is missing");root=new LinkedHashMap<>(Support.object(input.readAllBytes()));}
        var paths=new LinkedHashMap<String,Object>(Support.map(root.get("paths")));var components=new LinkedHashMap<String,Object>(Support.map(root.get("components")));var schemas=new LinkedHashMap<String,Object>(Support.map(components.get("schemas")));
        for(String role:List.of("agent","voice","media","recording","operations"))try(var input=ApiDocumentation.class.getResourceAsStream("/contracts/openapi-"+role+".json")){
            if(input==null)throw new IOException("service contract is missing");var contract=Support.object(input.readAllBytes());Support.map(contract.get("paths")).forEach((path,methods)->{if(!Set.of("/health","/readyz","/metrics","/internal/state","/internal/command").contains(path))paths.putIfAbsent(path,methods);});Support.map(Support.map(contract.get("components")).get("schemas")).forEach(schemas::putIfAbsent);
        }
        root.put("paths",paths);components.put("schemas",schemas);root.put("components",components);specification=Support.json(root);
    }
    @GetMapping(value="/openapi.json", produces="application/json") byte[] openapi() { return specification.clone(); }
    @GetMapping(value="/docs", produces="text/html;charset=UTF-8") ResponseEntity<String> docs() {
        String policy = "default-src 'none'; script-src 'sha256-" + digest(JS) + "'; style-src 'sha256-" + digest(CSS) + "'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'";
        String html = "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Java service API</title><style>" + CSS + "</style><body><h1 id=\"title\">Java service API</h1><p id=\"description\"></p><p><a href=\"/openapi.json\">OpenAPI JSON</a></p><p><small>Read-only reference. Authentication and signed-command requirements still apply to API calls.</small></p><label for=\"filter\">Find an endpoint</label><p><input id=\"filter\" placeholder=\"Method, path or description\"></p><p id=\"status\" role=\"status\">Loading API reference…</p><main id=\"operations\"></main><details><summary>Component schemas and authentication</summary><pre id=\"schemas\"></pre></details><script>" + JS + "</script></body></html>";
        return ResponseEntity.ok().header("Content-Security-Policy", policy).body(html);
    }
    private static String digest(String text) { return Base64.getEncoder().encodeToString(Support.sha(text.getBytes(StandardCharsets.UTF_8))); }
}

package com.duozhi.outbound;

import java.util.*;
import java.util.regex.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/script-templates/{template_id}/flows")
@AdminPermission("script-templates:write")
public class ScriptFlows {
    private final Store store;private final Contract contract;private final JsonSupport json;private final Transactions tx;
    public ScriptFlows(Store store,Contract contract,JsonSupport json,Transactions tx){this.store=store;this.contract=contract;this.json=json;this.tx=tx;}
    private long tenant(HttpServletRequest r){Identity i=Identity.from(r);i.admin("script-templates:write");return i.tenant();}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> list(Map<String,Object> graph,String name){return (List<Map<String,Object>>)graph.getOrDefault(name,Collections.emptyList());}
    @SuppressWarnings("unchecked") public Map<String,Object> graph(Map<String,Object> raw) {
        Map<String,Object> g=contract.input("ScriptFlowGraph",raw,true);
        for(String name:Arrays.asList("nodes","edges")) {
            List<Map<String,Object>> clean=new ArrayList<>();for(Map<String,Object> item:list(g,name)){Map<String,Object> node=contract.input("nodes".equals(name)?"FlowNode":"FlowEdge",item,true);if("edges".equals(name))node.putIfAbsent("keywords",new ArrayList<>());clean.add(node);}g.put(name,clean);
        }
        Map<String,Map<String,Object>> nodes=new LinkedHashMap<>();Set<String> edges=new HashSet<>();String start=null;
        for(Map<String,Object> n:list(g,"nodes")) {
            String id=n.get("id").toString();if(nodes.put(id,n)!=null)throw new ApiError(400,"duplicate node id");
            if("start".equals(n.get("type"))){if(start!=null)throw new ApiError(400,"flow must contain exactly one start node");start=id;}
            if(Values.set("collect","set").contains(n.get("type"))&&Objects.toString(n.get("variable"),"").isEmpty())throw new ApiError(400,"node requires a variable");
        }
        if(start==null)throw new ApiError(400,"flow must contain exactly one start node");
        Map<String,List<String>> outgoing=new HashMap<>();Set<String> special=new HashSet<>();
        for(Map<String,Object> e:list(g,"edges")) {
            String id=e.get("id").toString(),source=e.get("source").toString(),target=e.get("target").toString(),condition=e.get("condition").toString();
            if(!edges.add(id))throw new ApiError(400,"duplicate edge id");
            if(!nodes.containsKey(source)||!nodes.containsKey(target)||source.equals(target)||target.equals(start))throw new ApiError(400,"invalid edge endpoint");
            if(Values.set("handoff","hangup").contains(nodes.get(source).get("type")))throw new ApiError(400,"terminal node cannot have outgoing edges");
            if(Values.set("always","silence").contains(condition)&&!special.add(source+":"+condition))throw new ApiError(400,"duplicate default edge");
            Collection<?> words=(Collection<?>)e.getOrDefault("keywords",Collections.emptyList());
            if("keyword".equals(condition)&&words.stream().noneMatch(w->!w.toString().trim().isEmpty())||"intent".equals(condition)&&words.isEmpty())throw new ApiError(400,"edge requires keywords");
            if(Values.set("equals","not_equals").contains(condition)&&Objects.toString(e.get("variable"),"").isEmpty())throw new ApiError(400,"variable condition requires a variable");
            outgoing.computeIfAbsent(source,k->new ArrayList<>()).add(target);
        }
        for(Map<String,Object> n:nodes.values())if(!Values.set("handoff","hangup").contains(n.get("type"))&&!outgoing.containsKey(n.get("id")))throw new ApiError(400,"non-terminal node requires an outgoing edge");
        for(Map<String,Object> n:nodes.values())if(Values.set("set","branch").contains(n.get("type"))) {
            Set<String> visited=new HashSet<>();Deque<String> stack=new ArrayDeque<>(outgoing.getOrDefault(n.get("id"),Collections.emptyList()));
            while(!stack.isEmpty()){String target=stack.pop();if(target.equals(n.get("id")))throw new ApiError(400,"automatic nodes contain a cycle");if(Values.set("set","branch").contains(nodes.get(target).get("type"))&&visited.add(target))stack.addAll(outgoing.getOrDefault(target,Collections.emptyList()));}
        }
        Set<String> reached=new HashSet<>();Deque<String> stack=new ArrayDeque<>();stack.add(start);
        while(!stack.isEmpty()){String id=stack.pop();if(reached.add(id))stack.addAll(outgoing.getOrDefault(id,Collections.emptyList()));}
        if(reached.size()!=nodes.size())throw new ApiError(400,"unreachable nodes");
        if(nodes.values().stream().noneMatch(n->Values.set("handoff","hangup").contains(n.get("type"))))throw new ApiError(400,"flow requires a reachable terminal node");
        return g;
    }
    public Map<String,Object> simulate(Map<String,Object> g,Object currentId,String transcript,boolean silence,Map<String,Object> variables) {
        g=graph(g);Map<String,Map<String,Object>> nodes=new LinkedHashMap<>();Map<String,Object> current=null;
        for(Map<String,Object> n:list(g,"nodes")){nodes.put(n.get("id").toString(),n);if("start".equals(n.get("type")))current=n;}
        if(currentId!=null&&nodes.containsKey(currentId.toString()))current=nodes.get(currentId.toString());
        Map<String,Object> values=new LinkedHashMap<>(variables==null?Collections.emptyMap():variables);
        if("collect".equals(current.get("type"))) {
            if(transcript.trim().isEmpty()||Values.set("question","unclear").contains(DialogueRules.classify(transcript)))return JsonSupport.map("current_node_id",current.get("id"),"next_node_id",null,"action","speak","prompt",current.get("prompt"),"variables",values,"matched_edge_id",null);
            values.put(current.get("variable").toString(),transcript.trim().substring(0,Math.min(500,transcript.trim().length())));
        }
        if("set".equals(current.get("type")))values.put(current.get("variable").toString(),current.get("value"));
        List<Map<String,Object>> candidates=new ArrayList<>();for(Map<String,Object> e:list(g,"edges"))if(current.get("id").equals(e.get("source")))candidates.add(e);
        Map<String,Object> matched=null;
        for(String phase:Arrays.asList("silence","keyword","conditional","always")) {
            for(Map<String,Object> e:candidates) {
                String c=e.get("condition").toString();boolean match=false;
                if("silence".equals(phase))match="silence".equals(c)&&silence;
                else if("keyword".equals(phase)&&"keyword".equals(c)){for(Object word:(List<?>)e.get("keywords"))if(DialogueRules.keyword(transcript,word.toString()))match=true;}
                else if("conditional".equals(phase))match="intent".equals(c)&&((List<?>)e.get("keywords")).contains(DialogueRules.classify(transcript))||"equals".equals(c)&&values.containsKey(e.get("variable"))&&Objects.equals(values.get(e.get("variable")),e.get("value"))||"not_equals".equals(c)&&values.containsKey(e.get("variable"))&&!Objects.equals(values.get(e.get("variable")),e.get("value"));
                else if("always".equals(phase))match="always".equals(c);
                if(match){matched=e;break;}
            }
            if(matched!=null)break;
        }
        Map<String,Object> target=matched==null?null:nodes.get(matched.get("target"));String prompt=target==null?"":Objects.toString(target.get("prompt"),"");
        Matcher placeholder=Pattern.compile("\\{([a-zA-Z0-9_]+)\\}").matcher(prompt);StringBuffer rendered=new StringBuffer();
        while(placeholder.find())placeholder.appendReplacement(rendered,Matcher.quoteReplacement(Objects.toString(values.get(placeholder.group(1)),"（待确认）")));placeholder.appendTail(rendered);
        Map<String,String> actions=Values.map("message","speak","listen","listen","handoff","handoff","hangup","hangup","start","continue","collect","speak","set","continue","branch","continue");
        return JsonSupport.map("current_node_id",current.get("id"),"next_node_id",target==null?null:target.get("id"),"action",target==null?"wait":actions.get(target.get("type")),"prompt",rendered.toString(),"matched_edge_id",matched==null?null:matched.get("id"),"variables",values);
    }
    private Map<String,Object> version(long tenant,long template,Object id){Map<String,Object> row=store.require("scriptflowversion",tenant,id);if(!row.get("script_template_id").toString().equals(""+template))throw new ApiError(404,"flow version not found");return row;}
    private Map<String,Object> output(Map<String,Object> row){Map<String,Object> data=new LinkedHashMap<>(row);data.put("graph",graph(json.object(row.get("graph_json").toString())));return contract.output("ScriptFlowOut",data);}
    @GetMapping public List<Map<String,Object>> versions(@PathVariable long template_id,HttpServletRequest request){long tenant=tenant(request);store.require("scripttemplate",tenant,template_id);List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> row:store.query("scriptflowversion","templateVersions",Values.map("tenantId",tenant,"templateId",template_id)))result.add(output(row));return result;}
    @GetMapping("/{version_id}") public Map<String,Object> get(@PathVariable long template_id,@PathVariable long version_id,HttpServletRequest request){return output(version(tenant(request),template_id,version_id));}
    @PostMapping public Map<String,Object> create(@PathVariable long template_id,@RequestBody Map<String,Object> body,HttpServletRequest request){long tenant=tenant(request);Map<String,Object> input=contract.input("ScriptFlowCreate",body,true);return tx.run(()->{
        tx.platform();Map<String,Object> template=store.require("scripttemplate",tenant,template_id);
        int number=(int)store.scalar("scriptflowversion","maxTemplateVersion",Values.map("tenantId",tenant,"templateId",template_id))+1;
        Map<String,Object> g=defaults(Objects.toString(template.get("content"),""));if(input.get("clone_version_id")!=null)g=json.object(version(tenant,template_id,input.get("clone_version_id")).get("graph_json").toString());
        return output(store.insert("scriptflowversion",JsonSupport.map("tenant_id",tenant,"script_template_id",template_id,"version",number,"name",Objects.toString(input.get("name"),"").isEmpty()?template.get("name")+" v"+number:input.get("name"),"description",input.getOrDefault("description",""),"graph_json",json.json(graph(g)))));
    });}
    @PutMapping("/{version_id}") @SuppressWarnings("unchecked") public Map<String,Object> update(@PathVariable long template_id,@PathVariable long version_id,@RequestBody Map<String,Object> body,HttpServletRequest request){long tenant=tenant(request);Map<String,Object> input=contract.input("ScriptFlowUpdate",body,true);return tx.run(()->{
        tx.platform();Map<String,Object> row=version(tenant,template_id,version_id);if(!"draft".equals(row.get("status")))throw new ApiError(409,"published flow is immutable; clone a new draft");
        Map<String,Object> changes=JsonSupport.map("graph_json",json.json(graph((Map<String,Object>)input.get("graph"))));for(String key:Arrays.asList("name","description"))if(input.get(key)!=null)changes.put(key,input.get(key));return output(store.update("scriptflowversion",tenant,version_id,changes));
    });}
    @PostMapping("/{version_id}/publish") public Map<String,Object> publish(@PathVariable long template_id,@PathVariable long version_id,HttpServletRequest request){long tenant=tenant(request);return tx.run(()->{tx.platform();Map<String,Object> row=version(tenant,template_id,version_id);graph(json.object(row.get("graph_json").toString()));if("draft".equals(row.get("status")))row=store.update("scriptflowversion",tenant,version_id,JsonSupport.map("status","published","published_at",Store.now()));return output(row);});}
    @PostMapping("/{version_id}/simulate") @SuppressWarnings("unchecked") public Map<String,Object> probe(@PathVariable long template_id,@PathVariable long version_id,@RequestBody Map<String,Object> body,HttpServletRequest request){Map<String,Object> row=version(tenant(request),template_id,version_id),input=contract.input("ScriptFlowSimulateRequest",body,true);return simulate(json.object(row.get("graph_json").toString()),input.get("current_node_id"),Objects.toString(input.get("transcript"),""),Boolean.TRUE.equals(input.get("silence")),(Map<String,Object>)input.get("variables"));}
    private Map<String,Object> defaults(String opening) {
        List<Map<String,Object>> nodes=new ArrayList<>(),edges=new ArrayList<>();String[] ids={"start","opening","listen","hangup"},types={"start","message","listen","hangup"},labels={"开始","开场白","等待客户回答","结束通话"};
        for(int i=0;i<ids.length;i++){nodes.add(JsonSupport.map("id",ids[i],"type",types[i],"label",labels[i],"prompt",i==1?opening:"","position",JsonSupport.map("x",new int[]{80,320,580,840}[i],"y",180)));if(i>0)edges.add(JsonSupport.map("id","e-"+ids[i-1]+"-"+ids[i],"source",ids[i-1],"target",ids[i],"condition","always"));}
        return JsonSupport.map("nodes",nodes,"edges",edges);
    }
}

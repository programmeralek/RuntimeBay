package dev.runtimebay;

import com.google.gson.*;
import dev.runtimebay.Models.*;
import dev.runtimebay.WorkflowModels.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Portable YAML is reviewed into existing definitions; machine mappings remain private. */
final class WorkflowManifests {
    record Entry(String projectId, WorkflowYaml.Definition definition, Map<String,String> services, Map<String,String> environments, Map<String,String> hosts,Map<String,WorkflowAttachments.Source> references,Map<String,String> resourceIds,Map<String,String> attachmentIds,Set<String> createdLinks,Set<String> createdAttachments){}
    private record Registry(int version,List<Entry> entries){}
    record Preview(String revision,WorkflowYaml.Definition definition,Map<String,String> services,Map<String,String> environments,Map<String,String> hosts,Map<String,WorkflowAttachments.Source> references,List<String> changes,List<String> warnings){}
    record Export(String yaml,List<String> warnings){}
    record FileView(boolean exists,String yaml,String hash){}
    private final ProjectStore projects;private final WorkflowStore workflows;private final RuntimeManager runtimes;private final Path file;
    private final WorkflowContainers containers;private final WorkflowAttachments attachments;
    private final Gson gson=new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private List<Entry> entries=List.of();private volatile boolean unhealthy;
    WorkflowManifests(ProjectStore projects,RuntimeManager runtimes)throws IOException{this(projects,runtimes,new WorkflowContainers(projects,new DockerService(null)),new WorkflowAttachments(projects,runtimes.workflows()));}
    WorkflowManifests(ProjectStore projects,RuntimeManager runtimes,WorkflowContainers containers,WorkflowAttachments attachments)throws IOException{this.projects=projects;this.runtimes=runtimes;this.containers=containers;this.attachments=attachments;workflows=runtimes.workflows();file=projects.directory().resolve("workflow-manifests.json");reload();}
    synchronized void reload()throws IOException{
        entries=List.of();if(!Files.exists(file))return;
        try{
            var registry=gson.fromJson(Files.readString(file),Registry.class);if(registry==null||registry.version()!=1||registry.entries()==null)throw new IllegalArgumentException();
            Set<String> ids=new HashSet<>();for(var entry:registry.entries()){
                if(entry==null||entry.projectId()==null||!ids.add(entry.projectId())||entry.definition()==null||entry.services()==null||entry.environments()==null||entry.hosts()==null||entry.references()==null||entry.resourceIds()==null||entry.attachmentIds()==null||entry.createdLinks()==null||entry.createdAttachments()==null)throw new IllegalArgumentException();
                var d=WorkflowYaml.parse(WorkflowYaml.dump(map(entry.definition())));
                if(!entry.services().keySet().equals(d.processes().keySet())||new HashSet<>(entry.services().values()).size()!=entry.services().size()||!entry.environments().keySet().equals(d.environments().keySet())||!entry.hosts().keySet().equals(new HashSet<>(d.hosts()))||entry.services().values().stream().anyMatch(WorkflowManifests::invalidId)||entry.environments().values().stream().anyMatch(WorkflowManifests::invalidId)||entry.hosts().values().stream().anyMatch(WorkflowManifests::invalidId))throw new IllegalArgumentException();
                if(!entry.references().keySet().equals(WorkflowYaml.referenceKinds(d).keySet()))throw new IllegalArgumentException();
                for(var ref:entry.references().entrySet())if(ref.getValue()==null||!ref.getValue().kind().equals(WorkflowYaml.referenceKinds(d).get(ref.getKey()))||invalidId(ref.getValue().sourceId())||ref.getValue().projectId()==null||ref.getValue().scope()==null)throw new IllegalArgumentException();
                if(entry.resourceIds().values().stream().anyMatch(WorkflowManifests::invalidId)||entry.attachmentIds().values().stream().anyMatch(WorkflowManifests::invalidId)||entry.createdLinks().stream().anyMatch(WorkflowManifests::invalidId)||entry.createdAttachments().stream().anyMatch(WorkflowManifests::invalidId))throw new IllegalArgumentException();
            }
            entries=List.copyOf(registry.entries());
        }catch(RuntimeException error){throw new IOException("Cannot read workflow-manifests.json. Existing data is preserved; restore a valid manifest registry before reopening.",error);}
    }
    private static boolean invalidId(String id){return id==null||id.isBlank()||id.length()>128||id.chars().anyMatch(Character::isISOControl);}
    void requireHealthy(){if(unhealthy)throw new ApiException(503,"An import needs recovery. Restart Runtime Bay before editing or starting workloads.");}
    synchronized Entry get(String projectId){projects.project(projectId);return entries.stream().filter(e->e.projectId().equals(projectId)).findFirst().orElse(null);}
    synchronized boolean managed(String projectId,String id){var entry=get(projectId);return entry!=null&&entry.services().containsValue(id);}
    String revision(HostRegistry.View hosts)throws IOException{
        var text=new StringBuilder(gson.toJson(hosts));
        for(String name:DefinitionTransaction.FILES){Path p=projects.directory().resolve(name);text.append('\0').append(name).append('\0').append(Files.exists(p)?hash(Files.readAllBytes(p)):"missing");}
        return hash(text.toString().getBytes(StandardCharsets.UTF_8));
    }
    synchronized Preview preview(String projectId,String yaml,Map<String,String> mappings,HostRegistry.View hosts)throws IOException{return preview(projectId,yaml,mappings,Map.of(),hosts);}
    synchronized Preview preview(String projectId,String yaml,Map<String,String> mappings,Map<String,WorkflowAttachments.Source> referenceMappings,HostRegistry.View hosts)throws IOException{
        requireHealthy();var d=WorkflowYaml.parse(yaml);var project=projects.project(projectId);var current=workflows.get(projectId);var old=get(projectId);
        var resolved=new LinkedHashMap<String,String>();
        for(String alias:d.hosts()){
            String id=alias.equals("local")?"local":mappings.get(alias);
            if(id==null||hosts.hosts().stream().noneMatch(h->h.id().equals(id)))throw new ApiException(400,"Map logical host '"+alias+"' to an existing saved machine");
            resolved.put(alias,id);
        }
        var services=new LinkedHashMap<String,String>();var changes=new ArrayList<String>();var warnings=new ArrayList<String>();
        var references=new LinkedHashMap<String,WorkflowAttachments.Source>();var choices=attachments.choices(hosts,containers.all());
        for(var required:WorkflowYaml.referenceKinds(d).entrySet()){
            var source=referenceMappings.get(required.getKey());var choice=choices.stream().filter(c->c.source().equals(source)&&c.source().kind().equals(required.getValue())).findFirst().orElseThrow(()->new ApiException(400,"Map resource reference '"+required.getKey()+"' to an existing "+required.getValue()));
            if(source.kind().equals("service")&&source.projectId().equals(projectId))throw new ApiException(400,"Use a process binding for a service already configured in this workflow");references.put(required.getKey(),source);
        }
        Set<String> primaryContainers=new HashSet<>();
        for(var role:d.resources().entrySet())if(role.getValue().kind().equals("container")){
            String alias=d.environments().get(d.defaultEnvironment()).resources().get(role.getKey());var source=references.get(alias);var choice=choices.stream().filter(c->c.source().equals(source)).findFirst().orElseThrow();
            if(!primaryContainers.add(choice.key()))throw new ApiException(400,"Use one logical container role for the same default container target");
        }
        for(var environment:d.environments().entrySet())for(var role:environment.getValue().resources().entrySet())if(role.getValue()!=null){var source=references.get(role.getValue());var choice=choices.stream().filter(c->c.source().equals(source)).findFirst().orElseThrow();changes.add(environment.getValue().name()+" / resource "+role.getKey()+" → "+role.getValue()+" ("+choice.name()+") · reference only");}
        if(old!=null)warnings.add("Removed YAML-created associations are unlinked without changing source workloads. Pre-existing associations and shared container anchors are retained.");

        if(!project.name().equals(d.name()))changes.add("Rename workflow: "+project.name()+" → "+d.name());
        Set<String> claimed=new HashSet<>();
        for(var item:d.processes().entrySet()){
            String logical=item.getKey();var process=item.getValue();String oldId=old==null?null:old.services().get(logical);
            var existing=project.services().stream().filter(s->s.id().equals(oldId)).findFirst().orElse(null);
            if(existing==null&&oldId==null)existing=project.services().stream().filter(s->s.name().equalsIgnoreCase(process.name())).findFirst().orElse(null);
            String id=existing==null?WorkflowStore.stable("yaml-process",projectId+"\0"+logical):existing.id();
            if(!claimed.add(id))throw new ApiException(400,"Multiple YAML processes resolve to the same configured service");
            ProjectStore.resolveDirectory(project,process.directory());services.put(logical,id);
            boolean unchanged=existing!=null&&existing.name().equals(process.name())&&existing.command().equals(process.command())&&existing.directory().equals(process.directory())&&Objects.equals(existing.port(),process.port());
            changes.add((existing==null?"Add ":unchanged?"Keep ":"Update ")+logical+" · "+process.mode()+" · "+process.command());
        }
        if(old!=null)for(var item:old.services().entrySet())if(!services.containsValue(item.getValue())&&project.services().stream().anyMatch(s->s.id().equals(item.getValue())))changes.add("Remove YAML-managed definition: "+item.getKey()+" (no workload is deleted)");
        long retained=project.services().stream().filter(s->!claimed.contains(s.id())&&(old==null||!old.services().containsValue(s.id()))).count();if(retained>0)warnings.add(retained+" existing service definitions are retained outside the YAML stack");
        var environments=new LinkedHashMap<String,String>();Set<String> usedEnvironments=new HashSet<>();
        for(var item:d.environments().entrySet()){
            String oldId=old==null?null:old.environments().get(item.getKey());
            var found=current.environments().stream().filter(e->e.id().equals(oldId)).findFirst().orElse(null);
            if(found==null)found=current.environments().stream().filter(e->e.name().equalsIgnoreCase(item.getValue().name())).findFirst().orElse(null);
            String id=found==null?WorkflowStore.stable("yaml-environment",projectId+"\0"+item.getKey()):found.id();
            if(!usedEnvironments.add(id))throw new ApiException(400,"Multiple YAML environments map to the same saved environment");
            environments.put(item.getKey(),id);changes.add((found==null?"Add ":"Update ")+"environment "+item.getValue().name());
            for(var binding:item.getValue().bindings().entrySet()){
                String hostId=resolved.get(binding.getValue());String label=hosts.hosts().stream().filter(h->h.id().equals(hostId)).findFirst().orElseThrow().name();
                changes.add(item.getValue().name()+" / "+binding.getKey()+" → "+binding.getValue()+" ("+label+")");
            }
        }
        var combined=new HashMap<String,String>();for(var environment:current.environments())combined.put(environment.id(),environment.name());
        for(var environment:d.environments().entrySet())combined.put(environments.get(environment.getKey()),environment.getValue().name());
        if(combined.size()>20||combined.values().stream().map(v->v.toLowerCase(Locale.ROOT)).distinct().count()!=combined.size())throw new ApiException(400,"Import would exceed 20 environments or duplicate an existing environment name");
        var serviceNames=new HashSet<String>();for(var process:d.processes().values())serviceNames.add(process.name().toLowerCase(Locale.ROOT));
        for(var service:project.services())if(!claimed.contains(service.id())&&(old==null||!old.services().containsValue(service.id()))&&!serviceNames.add(service.name().toLowerCase(Locale.ROOT)))throw new ApiException(409,"A retained service already uses the imported name: "+service.name());
        changes.add("Select environment: "+d.environments().get(d.defaultEnvironment()).name());
        warnings.add("Import only saves definitions. Commands, CLI tasks, and infrastructure are never executed by import or file loading.");
        warnings.add("Existing resources outside this manifest and undeclared environments are retained. External resources are linked by logical reference aliases; import never creates or adopts their workloads.");
        warnings.add("Review commands before sharing: arbitrary shell text can contain credentials, private URLs, and machine-specific paths. Environment references are stored unresolved.");
        if(resolved.values().stream().anyMatch(id->!id.equals("local")))warnings.add("Remote bindings can be saved, but remote service/task execution is not available yet. Local fallback is disabled.");
        return new Preview(revision(hosts),d,Map.copyOf(services),Map.copyOf(environments),Map.copyOf(resolved),Map.copyOf(references),List.copyOf(changes),List.copyOf(warnings));
    }
    synchronized Entry apply(String projectId,String yaml,Map<String,String> mappings,String expected,HostRegistry.View hosts)throws Exception{return apply(projectId,yaml,mappings,Map.of(),expected,hosts);}
    synchronized Entry apply(String projectId,String yaml,Map<String,String> mappings,Map<String,WorkflowAttachments.Source> references,String expected,HostRegistry.View hosts)throws Exception{
        var preview=preview(projectId,yaml,mappings,references,hosts);if(expected==null||!expected.equals(preview.revision()))throw new ApiException(409,"Definitions or host mappings changed. Preview the YAML again before applying it.");
        return runtimes.withStoppedDefinitions(projectId,List.copyOf(preview.services().values()),()->{
            var transaction=new DefinitionTransaction(projects.directory());
            try{
                var old=get(projectId);var d=preview.definition();var nextServices=new ArrayList<Service>();
                for(var service:projects.project(projectId).services())if(!preview.services().containsValue(service.id())&&(old==null||!old.services().containsValue(service.id())))nextServices.add(service);
                for(var item:d.processes().entrySet()){var p=item.getValue();String serviceId=preview.services().get(item.getKey());Service previous=projects.project(projectId).services().stream().filter(s->s.id().equals(serviceId)).findFirst().orElse(null);nextServices.add(new Service(serviceId,p.name(),p.command(),p.directory(),p.port(),previous==null?null:previous.dependsOnResourceId()));}
                projects.importServices(projectId,d.name(),nextServices);
                var createdLinks=new HashSet<String>(old==null?Set.of():old.createdLinks());var createdAttachments=new HashSet<String>(old==null?Set.of():old.createdAttachments());
                var resourceIds=new LinkedHashMap<String,String>();var attachmentIds=new LinkedHashMap<String,String>();
                var allLinks=containers.all();
                for(var role:d.resources().entrySet())if(role.getValue().kind().equals("container")){
                    String existingId=old==null?null:old.resourceIds().get(role.getKey());
                    var existing=containers.list(projectId).stream().filter(l->l.id().equals(existingId)).findFirst().orElse(null);
                    if(existing==null){var source=preview.references().get(d.environments().get(d.defaultEnvironment()).resources().get(role.getKey()));var target=link(source,allLinks);var before=containers.list(projectId);existing=containers.record(projectId,target.context(),target.endpoint(),target.containerId(),target.name(),target.image());String id=existing.id();if(before.stream().noneMatch(l->l.id().equals(id)))createdLinks.add(id);}
                    resourceIds.put(role.getKey(),existing.id());
                }
                for(String id:new HashSet<>(createdLinks))if(!resourceIds.containsValue(id)&&preview.references().values().stream().noneMatch(r->r.kind().equals("container")&&r.projectId().equals(projectId)&&r.sourceId().equals(id))){
                    try{workflows.requireUnsharedLink(id);if(containers.list(projectId).stream().anyMatch(l->l.id().equals(id)))containers.remove(projectId,id);createdLinks.remove(id);}catch(ApiException shared){if(shared.status!=409)throw shared;}
                }
                var base=workflows.get(projectId);var environments=new ArrayList<>(base.environments());
                for(var item:d.environments().entrySet()){String id=preview.environments().get(item.getKey());environments.removeIf(e->e.id().equals(id));environments.add(new WorkflowModels.Environment(id,item.getValue().name()));}
                var machines=new ArrayList<>(base.machines());for(String hostId:preview.hosts().values())if(machines.stream().noneMatch(m->m.id().equals(hostId)))machines.add(new MachineReference(hostId));
                var connections=new LinkedHashMap<String,DockerConnection>();for(var connection:base.dockerConnections())connections.put(connection.id(),connection);
                for(var environment:d.environments().values())for(var role:environment.resources().entrySet())if(role.getValue()!=null&&d.resources().get(role.getKey()).kind().equals("container")){var target=link(preview.references().get(role.getValue()),containers.all());String id=WorkflowStore.stable("docker",target.context()+"\0"+target.endpoint());connections.putIfAbsent(id,new DockerConnection(id,target.context(),target.endpoint(),null));}
                var bindings=new ArrayList<Binding>();
                for(var environment:environments)for(var resource:base.resources()){
                    var previous=base.bindings().stream().filter(b->b.environmentId().equals(environment.id())&&b.resourceId().equals(resource.id())).findFirst().orElse(null);
                    if(previous==null){var template=base.bindings().stream().filter(b->b.environmentId().equals(base.defaultEnvironmentId())&&b.resourceId().equals(resource.id())).findFirst().orElseThrow();previous=new Binding(WorkflowStore.stable("binding",environment.id()+"\0"+resource.id()),environment.id(),resource.id(),template.target());}
                    Target target=previous.target();
                    for(var declared:d.environments().entrySet())if(preview.environments().get(declared.getKey()).equals(environment.id()))for(var source:preview.services().entrySet())if(source.getValue().equals(resource.id()))target=new Target("machine",preview.hosts().get(declared.getValue().bindings().get(source.getKey())));
                    for(var declared:d.environments().entrySet())if(preview.environments().get(declared.getKey()).equals(environment.id()))for(var role:resourceIds.entrySet())if(role.getValue().equals(resource.id())){String alias=declared.getValue().resources().get(role.getKey());var source=preview.references().get(alias);var external=link(source,containers.all());target=new Target("docker",WorkflowStore.stable("docker",external.context()+"\0"+external.endpoint()),external.id());}
                    bindings.add(new Binding(previous.id(),environment.id(),resource.id(),target));
                }
                workflows.save(projectId,new Workflow(base.id(),base.projectId(),base.name(),preview.environments().get(d.defaultEnvironment()),List.copyOf(environments),List.copyOf(machines),List.copyOf(connections.values()),base.resources(),List.copyOf(bindings)));
                for(var environment:d.environments().entrySet())for(var role:environment.getValue().resources().entrySet())if(role.getValue()!=null&&!d.resources().get(role.getKey()).kind().equals("container")){
                    String key=environment.getKey()+"/"+role.getKey();String previous=old==null?null:old.attachmentIds().get(key);var source=preview.references().get(role.getValue());
                    var previousEntry=attachments.all(projectId).stream().filter(a->a.id().equals(previous)&&a.source().kind().equals(source.kind())).findFirst().orElse(null);
                    var before=attachments.all(projectId);var attached=attachments.importReference(projectId,preview.environments().get(environment.getKey()),previousEntry==null?null:previousEntry.id(),source,hosts,containers.all());attachmentIds.put(key,attached.id());
                    if(before.stream().noneMatch(a->a.id().equals(attached.id())))createdAttachments.add(attached.id());
                }
                for(String id:new HashSet<>(createdAttachments))if(!attachmentIds.containsValue(id)){if(attachments.all(projectId).stream().anyMatch(a->a.id().equals(id)))attachments.unlinkImported(projectId,id);createdAttachments.remove(id);}
                var entry=new Entry(projectId,d,preview.services(),preview.environments(),preview.hosts(),preview.references(),Map.copyOf(resourceIds),Map.copyOf(attachmentIds),Set.copyOf(createdLinks),Set.copyOf(createdAttachments));var updated=new ArrayList<>(entries);updated.removeIf(e->e.projectId().equals(projectId));updated.add(entry);
                DefinitionTransaction.write(file,gson.toJson(new Registry(1,List.copyOf(updated))).getBytes(StandardCharsets.UTF_8));entries=List.copyOf(updated);transaction.commit();return entry;
            }catch(Exception error){
                try{transaction.rollback();projects.reload();containers.reload();attachments.reload();workflows.reload();reload();}catch(Exception recovery){unhealthy=true;error.addSuppressed(recovery);}
                throw error;
            }
        });
    }
    synchronized Export export(String projectId)throws IOException{
        var project=projects.project(projectId);var graph=workflows.get(projectId);var stored=get(projectId);var warnings=new ArrayList<String>();
        if(project.services().isEmpty()&&graph.resources().stream().noneMatch(r->r.type().equals("container"))&&attachments.all(projectId).isEmpty())return new Export(starter(project.name()),List.of("Starter example: this workflow has no configured services. Edit the sample before importing."));
        var names=new LinkedHashMap<String,String>();Set<String> used=new HashSet<>();
        for(var service:project.services()){
            String key=stored==null?null:stored.services().entrySet().stream().filter(e->e.getValue().equals(service.id())).map(Map.Entry::getKey).findFirst().orElse(null);
            if(key==null)key="process-"+service.id().substring(0,8);while(!used.add(key))key+="-next";names.put(service.id(),key);
        }
        var aliases=new LinkedHashMap<String,String>();aliases.put("local","local");
        if(stored!=null)for(var host:stored.hosts().entrySet())if(!aliases.containsKey(host.getValue()))aliases.put(host.getValue(),host.getKey());
        var processMap=new LinkedHashMap<String,Object>();
        for(var service:project.services()){
            String key=names.get(service.id());var old=stored==null?null:stored.definition().processes().get(key);var p=new LinkedHashMap<String,Object>();
            p.put("name",service.name());p.put("command",service.command());p.put("directory",service.directory());if(service.port()!=null)p.put("port",service.port());p.put("mode",old==null?"service":old.mode());
            if(old!=null){p.put("includeInStack",old.includeInStack());p.put("timeoutSeconds",old.timeoutSeconds());if(!old.dependsOn().isEmpty())p.put("dependsOn",old.dependsOn());if(!old.env().isEmpty())p.put("env",old.env());}
            processMap.put(key,p);
        }
        var envMap=new LinkedHashMap<String,Object>();var environmentKeys=new LinkedHashMap<String,String>();String selected=null;Set<String> envKeys=new HashSet<>();
        for(var environment:graph.environments()){
            String key=stored==null?null:stored.environments().entrySet().stream().filter(e->e.getValue().equals(environment.id())).map(Map.Entry::getKey).findFirst().orElse(null);
            if(key==null)key="environment-"+(envMap.size()+1);while(!envKeys.add(key))key+="-next";
            var bindings=new LinkedHashMap<String,String>();for(var service:project.services()){
                var binding=graph.bindings().stream().filter(b->b.environmentId().equals(environment.id())&&b.resourceId().equals(service.id())).findFirst().orElseThrow();String hostId=binding.target().id();
                if(!aliases.containsKey(hostId)){String alias="host-"+aliases.size();while(aliases.containsValue(alias))alias+="-next";aliases.put(hostId,alias);}bindings.put(names.get(service.id()),aliases.get(hostId));
            }
            var e=new LinkedHashMap<String,Object>();e.put("name",environment.name());e.put("bindings",bindings);if(stored!=null){var old=stored.definition().environments().get(key);if(old!=null&&!old.env().isEmpty())e.put("env",old.env());}envMap.put(key,e);environmentKeys.put(environment.id(),key);if(environment.id().equals(graph.defaultEnvironmentId()))selected=key;
        }
        var root=new LinkedHashMap<String,Object>();root.put("apiVersion",WorkflowYaml.VERSION);root.put("kind","Workflow");root.put("name",project.name());root.put("hosts",new ArrayList<>(new LinkedHashSet<>(aliases.values())));root.put("defaultEnvironment",selected);root.put("processes",processMap);root.put("environments",envMap);appendReferences(root,envMap,environmentKeys,graph,stored,warnings);
        warnings.add("Export contains logical host aliases, not private machine addresses or IDs. Map aliases on import.");warnings.add("Review shell commands before sharing: their contents can include private paths or inline credentials. No credential values are resolved by export.");
        warnings.add("External resources are exported as logical reference aliases; map them to saved resources on import. Global appearance preferences and layouts stay private and are not imported.");
        String yaml=WorkflowYaml.dump(root);try{WorkflowYaml.parse(yaml);}catch(ApiException error){warnings.add("Export needs editing before import: "+error.getMessage());}return new Export(yaml,List.copyOf(warnings));
    }
    @SuppressWarnings("unchecked")
    private void appendReferences(Map<String,Object> root,Map<String,Object> environments,Map<String,String> environmentKeys,Workflow graph,Entry stored,List<String> warnings){
        var roles=new LinkedHashMap<String,Map<String,WorkflowAttachments.Source>>();var kinds=new LinkedHashMap<String,String>();Set<String> represented=new HashSet<>();
        var links=containers.all();
        for(var resource:graph.resources())if(resource.type().equals("container")){
            String role=stored==null?null:stored.resourceIds().entrySet().stream().filter(e->e.getValue().equals(resource.id())).map(Map.Entry::getKey).findFirst().orElse(null);
            if(role==null)role="container-"+(roles.size()+1);while(roles.containsKey(role))role+="-next";var targets=new LinkedHashMap<String,WorkflowAttachments.Source>();
            for(var environment:graph.environments()){
                var binding=graph.bindings().stream().filter(b->b.environmentId().equals(environment.id())&&b.resourceId().equals(resource.id())).findFirst().orElseThrow();String id=binding.target().linkId()==null?resource.id():binding.target().linkId();
                var link=links.stream().filter(l->l.id().equals(id)).findFirst().orElse(null);
                if(link==null)warnings.add("Container role "+role+" has a missing target; map it explicitly on import");
                targets.put(environment.id(),new WorkflowAttachments.Source("container",link==null?"":link.projectId(),id,""));
            }
            roles.put(role,targets);kinds.put(role,"container");
        }
        var attached=attachments.all(graph.projectId());
        if(stored!=null)for(var mapping:stored.attachmentIds().entrySet()){
            String role=mapping.getKey().substring(mapping.getKey().indexOf('/')+1);var entry=attached.stream().filter(a->a.id().equals(mapping.getValue())).findFirst().orElse(null);if(entry==null||!environmentKeys.containsKey(entry.environmentId()))continue;
            if(kinds.containsKey(role)&&!kinds.get(role).equals(entry.source().kind()))continue;
            roles.computeIfAbsent(role,ignored->new LinkedHashMap<>()).put(entry.environmentId(),entry.source());kinds.put(role,entry.source().kind());represented.add(entry.id());
        }
        for(var entry:attached)if(!represented.contains(entry.id())){
            if(!environmentKeys.containsKey(entry.environmentId())){warnings.add("A reference belongs to a missing environment and cannot be exported until resolved");continue;}
            String role=entry.source().kind()+"-"+(roles.size()+1);while(roles.containsKey(role))role+="-next";var targets=new LinkedHashMap<String,WorkflowAttachments.Source>();targets.put(entry.environmentId(),entry.source());roles.put(role,targets);kinds.put(role,entry.source().kind());
        }
        var aliases=new LinkedHashMap<String,WorkflowAttachments.Source>();var resourceMap=new LinkedHashMap<String,Object>();
        for(var role:roles.entrySet()){
            var selected=role.getValue().getOrDefault(graph.defaultEnvironmentId(),role.getValue().values().iterator().next());String alias=exportAlias(selected,stored,aliases);
            resourceMap.put(role.getKey(),Map.of("kind",kinds.get(role.getKey()),"reference",alias));
            for(var environment:environmentKeys.entrySet()){
                var target=role.getValue().get(environment.getKey());var e=(Map<String,Object>)environments.get(environment.getValue());
                var selectedResources=(Map<String,String>)e.computeIfAbsent("resources",ignored->new LinkedHashMap<String,String>());
                selectedResources.put(role.getKey(),target==null?null:exportAlias(target,stored,aliases));
            }
        }
        root.put("resources",resourceMap);
    }
    private static String exportAlias(WorkflowAttachments.Source source,Entry stored,Map<String,WorkflowAttachments.Source> aliases){
        for(var alias:aliases.entrySet())if(alias.getValue().equals(source))return alias.getKey();
        String name=stored==null?null:stored.references().entrySet().stream().filter(e->e.getValue().equals(source)).map(Map.Entry::getKey).findFirst().orElse(null);
        if(name==null)name=source.kind()+"-ref-"+(aliases.size()+1);while(aliases.containsKey(name))name+="-next";aliases.put(name,source);return name;
    }
    FileView readFile(String projectId)throws IOException{
        Path path=manifestPath(projectId);if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return new FileView(false,"","missing");if(Files.size(path)>192000)throw new ApiException(400,"runtimebay.yaml is too large");
        byte[] data=Files.readAllBytes(path);return new FileView(true,new String(data,StandardCharsets.UTF_8),hash(data));
    }
    void saveFile(String projectId,String yaml,String expected)throws IOException{
        WorkflowYaml.parse(yaml);var current=readFile(projectId);if(expected==null||!expected.equals(current.hash()))throw new ApiException(409,"runtimebay.yaml changed. Load the current file before overwriting it.");
        DefinitionTransaction.write(manifestPath(projectId),yaml.getBytes(StandardCharsets.UTF_8));
    }
    private Path manifestPath(String projectId)throws IOException{Path root=Path.of(projects.project(projectId).path()).toRealPath();Path path=root.resolve("runtimebay.yaml");if(Files.isSymbolicLink(path)||Files.exists(path)&&!Files.isRegularFile(path))throw new ApiException(400,"runtimebay.yaml must be a regular file inside this project, not a symbolic link");return path;}
    static Map<String,Object> map(WorkflowYaml.Definition d){
        var root=new LinkedHashMap<String,Object>();root.put("apiVersion",d.apiVersion());root.put("kind",d.kind());root.put("name",d.name());root.put("hosts",d.hosts());root.put("defaultEnvironment",d.defaultEnvironment());
        var processes=new LinkedHashMap<String,Object>();for(var item:d.processes().entrySet()){var p=item.getValue();var v=new LinkedHashMap<String,Object>();v.put("name",p.name());v.put("command",p.command());v.put("directory",p.directory());if(p.port()!=null)v.put("port",p.port());v.put("mode",p.mode());v.put("includeInStack",p.includeInStack());v.put("timeoutSeconds",p.timeoutSeconds());v.put("dependsOn",p.dependsOn());v.put("env",p.env());processes.put(item.getKey(),v);}root.put("processes",processes);
        var environments=new LinkedHashMap<String,Object>();for(var item:d.environments().entrySet())environments.put(item.getKey(),Map.of("name",item.getValue().name(),"bindings",item.getValue().bindings(),"resources",item.getValue().resources(),"env",item.getValue().env()));var resources=new LinkedHashMap<String,Object>();for(var item:d.resources().entrySet())resources.put(item.getKey(),Map.of("kind",item.getValue().kind(),"reference",item.getValue().reference()));root.put("resources",resources);root.put("environments",environments);return root;
    }
    List<WorkflowAttachments.Choice> choices(HostRegistry.View hosts){return attachments.choices(hosts,containers.all());}
    private static WorkflowContainers.Link link(WorkflowAttachments.Source source,List<WorkflowContainers.Link> links){return links.stream().filter(l->l.id().equals(source.sourceId())&&l.projectId().equals(source.projectId())).findFirst().orElseThrow(()->new ApiException(409,"A saved container reference is missing. Resolve its logical alias before import."));}
    static String starter(String name){return "apiVersion: runtimebay.dev/v1\nkind: Workflow\nname: "+new Gson().toJson(name)+"\nhosts: [local]\ndefaultEnvironment: local\nprocesses:\n  setup:\n    name: Setup check\n    mode: task\n    command: printf 'Configure your setup command here\\n'\n  app:\n    name: Application\n    command: sleep 3600\n    dependsOn: [setup]\nenvironments:\n  local:\n    name: Local\n    bindings: {setup: local, app: local}\n";}
    static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}

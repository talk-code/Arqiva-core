package org.emerjoin.arqiva.core;

import org.emerjoin.arqiva.core.components.MarkdownParser;
import org.emerjoin.arqiva.core.components.TemplateEngine;
import org.emerjoin.arqiva.core.context.ContextValues;
import org.emerjoin.arqiva.core.context.HTMLRenderingContext;
import org.emerjoin.arqiva.core.context.MarkdownRenderingContext;
import org.emerjoin.arqiva.core.context.index.IndexPageRenderingCtx;
import org.emerjoin.arqiva.core.context.index.IndexRenderingContext;
import org.emerjoin.arqiva.core.context.topic.AbstractTopicRenderingContext;
import org.emerjoin.arqiva.core.context.topic.TopicRenderingContext;
import org.emerjoin.arqiva.core.context.topic.TopicRenderingCtx;
import org.emerjoin.arqiva.core.exception.TopicReferenceNotFoundException;
import org.emerjoin.arqiva.core.jandex.JandexModulesFinder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;


/**
 * Represents the Arqiva public API.
 * This class is not Thread-safe class.
 * @author Mário Júnior
 */
public class Arqiva {


    public static final String START_POINT_INDEX = "DEFAULT";
    public static final String START_POINT_FIRST_TOPIC = "FIRST_TOPIC";


    private static ModulesFinder MODULES_FINDER = new JandexModulesFinder();
    public static final String PROJECT_THEME_HTML_TEMPLATE = "project-theme";
    public static final String INDEX_PAGE_TEMPLATE = "index-page";
    public static final String TOPIC_PAGE_TEMPLATE = "topic-page";
    private String startPoint=START_POINT_INDEX;
    private Map<String,Object> buildHints = new HashMap<>();

    private static final Logger log = LoggerFactory.getLogger(Arqiva.class);

    public static void overrideModulesFinder(ModulesFinder modulesFinder){

        MODULES_FINDER = modulesFinder;

    }

    public static ModulesFinder getModulesFinder(){

        return MODULES_FINDER;

    }

    private Project project;
    private boolean ready = false;

    public Arqiva(Project project){

        this.project = project;

    }

    public void buildProject(String builderName){

        checkReady();

        if(builderName==null)
            throw new NullPointerException("Builder name is null. It might be empty but never null");

        synchronized (project) {

            ArqivaProjectContext context = (ArqivaProjectContext) project.getContext();
            ProjectBuilder projectBuilder = context.getDefaultBuilder();

            if (builderName.equals("") && projectBuilder == null)
                throw new ArqivaException(String.format("No %s instance set.", ProjectBuilder.class.getSimpleName()));

            else if(!builderName.equals("")&&projectBuilder == null){

                if (context.builderExists(builderName))
                    projectBuilder = context.getBuilder(builderName);
                else
                    throw new ArqivaException(String.format("Project Builder %s not found.", builderName));


            }

            //Call the builder
            projectBuilder.build(this);


        }



    }


    public String renderTopic(File topicFile){

        return renderTopic(topicFile,false);

    }

    public String renderTopic(File topicFile, boolean noAdjusts){

        synchronized (project) {

            checkReady();
            TopicReference topicReference = getTopicReference(topicFile,null);

            return renderTopic(topicReference,noAdjusts);

        }

    }

    private TopicReference getTopicReference(File file, String url){

        TopicReference topicReference = null;

        if(file!=null)
            topicReference = TopicReference.get(file, project);
        else
            topicReference = TopicReference.get(url, project);

        if (topicReference == null)
            throw new TopicReferenceNotFoundException((file!=null)?file.getAbsolutePath():url);

        return topicReference;


    }

    public String renderTopic(String topic){

        return renderTopic(topic,false);

    }

    public String renderTopic(String topic, boolean noAdjust){

        synchronized (project) {

            checkReady();
            TopicReference topicReference = getTopicReference(null,topic);
            return renderTopic(topicReference,noAdjust);

        }


    }


    private boolean getBoolean(ContextValues contextValues, String name, boolean defaultValue){

        if(!contextValues.hasValue(name))
            return defaultValue;

        String value = contextValues.getValue(name).toString();

        try {

            return Boolean.parseBoolean(value);

        }catch (Throwable ex){

            return defaultValue;

        }

    }


    private void escapeBlock(Element element){

        String escaped = String.format("#[[%s]]#",element.html());
        element.html(escaped);

    }

    private void escapeDocument(Document document){

        document.select("script").forEach(this::escapeBlock);
        document.select("code").forEach(this::escapeBlock);

    }

    private String escapeElements(String html){

        Document document = null;

        boolean containsBody = html.contains("body");
        if(containsBody){
            document = Jsoup.parse(html);
            escapeDocument(document);
            return document.outerHtml();
        }

        document =Jsoup.parseBodyFragment(html);
        escapeDocument(document);
        return document.body().outerHtml();

    }


    private String htmlPreventiveEscaping(String html, ContextValues values){

        boolean preventiveEscaping = getBoolean(values,ArqivaConstants.OPTION_HTML_PREVENTIVE_ESCAPE,true);
        if(!preventiveEscaping)
            return html;

        return escapeElements(html);

    }

    private String renderHTMLPlusMarkdown(TopicRenderingContext renderingContext, boolean noAdjusts){

        LifecycleExecutor lifecycleExecutor = new LifecycleExecutor(project.getContext());
        lifecycleExecutor.beforeCompile((MarkdownRenderingContext) renderingContext);


        String compiledMarkdown = project.getContext().getMarkdownParser().toHTML(renderingContext.getMarkdown());

        boolean preEscapeMarkdown = getBoolean(renderingContext,ArqivaConstants.OPTION_MARKDOWN_TEMPLATE_ENGINE_PRE_ESCAPE,true);
        if(preEscapeMarkdown)
            compiledMarkdown = project.getContext().getTemplateEngine().escape(compiledMarkdown);

        ((AbstractTopicRenderingContext) renderingContext).setCompiledMarkdown(compiledMarkdown);

        boolean markdownBypassTemplateEngine = getBoolean(renderingContext, ArqivaConstants.OPTION_MARKDOWN_BYPASS_TEMPLATE_ENGINE,false);

        if(preEscapeMarkdown&&markdownBypassTemplateEngine)
            log.warn("Markdown html output escaping is active while markdown template engine bypass is also active");


        String lifecycleHtmlInput = htmlPreventiveEscaping(renderingContext.getHtml(),renderingContext);
        if(!markdownBypassTemplateEngine)
            lifecycleHtmlInput = merge(compiledMarkdown,renderingContext.getHtml());

        renderingContext.updateHtml(lifecycleHtmlInput);

        lifecycleExecutor.afterCompile((MarkdownRenderingContext) renderingContext);
        lifecycleExecutor.beforeCompile((HTMLRenderingContext) renderingContext);
        project.getContext().getTemplateEngine().run(renderingContext);
        lifecycleExecutor.afterCompile((HTMLRenderingContext) renderingContext);

        String adjusted = renderingContext.getHtml();
        if(!noAdjusts)
            adjusted = adjustAssetsAndTopicsLinks(renderingContext.getHtml(),renderingContext.getTopicReference());

        renderingContext.updateHtml(adjusted);

        if(markdownBypassTemplateEngine)
                return merge(compiledMarkdown,renderingContext.getHtml());

        return renderingContext.getHtml();

    }

    private String renderTopic(TopicReference topicReference,boolean noAdjusts){

        String topicMarkdown = topicReference.getMarkdownContent();
        String topicTemplateHtml = project.getHTMLTemplate(TOPIC_PAGE_TEMPLATE);
        TopicRenderingContext renderingContext = new TopicRenderingCtx(project.getContext(),topicReference,
                topicTemplateHtml,topicMarkdown);

        return renderHTMLPlusMarkdown(renderingContext,noAdjusts);

    }

    private String renderTopicPage(TopicReference topicReference, boolean noAdjusts){

        String topicMarkdown = topicReference.getMarkdownContent();
        String topicTemplateHtml = project.getHTMLTemplate(TOPIC_PAGE_TEMPLATE);
        String themeTemplateHtml = project.getHTMLTemplate(PROJECT_THEME_HTML_TEMPLATE);

        TopicRenderingContext renderingContext = new TopicRenderingCtx(project.getContext(),topicReference,
                merge(topicTemplateHtml,themeTemplateHtml),topicMarkdown);

        return renderHTMLPlusMarkdown(renderingContext,noAdjusts);

    }


    public String renderTopicPage(File topicFile, boolean noAdjusts){

        synchronized (project) {
            checkReady();

            TopicReference topicReference = getTopicReference(topicFile,null);
            return renderTopicPage(topicReference,noAdjusts);

        }


    }

    public String renderTopicPage(File topicFile){

        return renderTopicPage(topicFile,false);

    }


    public String renderTopicPage(String topic){

        return renderTopicPage(topic,false);

    }

    public String renderTopicPage(String topic, boolean noAdjusts){

        synchronized (project) {

            checkReady();
            return renderTopicPage(getTopicReference(null,topic),noAdjusts);

        }

    }


    private String renderHtml(HTMLRenderingContext renderingContext){

        renderingContext.updateHtml(htmlPreventiveEscaping(renderingContext.getHtml(),renderingContext));

        LifecycleExecutor lifecycleExecutor = new LifecycleExecutor(project.getContext());
        lifecycleExecutor.beforeCompile(renderingContext);
        project.getContext().getTemplateEngine().run(renderingContext);
        lifecycleExecutor.afterCompile(renderingContext);
        return renderingContext.getHtml();

    }

    public String renderIndex(){

        synchronized (project) {

            checkReady();
            String indexHtml = project.getHTMLTemplate(INDEX_PAGE_TEMPLATE);
            IndexRenderingContext renderingContext = new IndexPageRenderingCtx(project.getContext(), indexHtml);
            return renderHtml(renderingContext);

        }

    }

    public String renderIndexPage(){

        synchronized (project) {
            checkReady();
            String indexHtml = project.getHTMLTemplate(INDEX_PAGE_TEMPLATE);
            String themeHtml = project.getHTMLTemplate(PROJECT_THEME_HTML_TEMPLATE);
            String mergedHtml = merge(indexHtml, themeHtml);
            IndexPageRenderingCtx renderingCtx = new IndexPageRenderingCtx(project.getContext(), mergedHtml);
            return renderHtml(renderingCtx);
        }

    }

    private void checkReady(){
        if(!ready)
            getReady();

    }

    public synchronized void getReady(){

        synchronized (project) {

            //Initialize modules (modules may override and configure build components)
            initModules();

            //Start build components
            if (project.getContext().getMarkdownParser() == null)
                throw new ArqivaException(String.format("No %s component instance set", MarkdownParser.class.getSimpleName()));
            project.getContext().getMarkdownParser().startComponent();


            if (project.getContext().getTemplateEngine() == null)
                throw new ArqivaException(String.format("No %s component instance set", TemplateEngine.class.getSimpleName()));
            project.getContext().getTemplateEngine().startComponent();

            ready = true;

        }


    }



    private void initModules(){

        final List<Module> modulesList = new ArrayList<Module>();

        //Load modules and ask them to override the build components
        MODULES_FINDER.findModules(new Function<String, Void>() {

            public Void apply(String clazz) {

                ModuleInfo moduleInfo = new ModuleInfo(clazz);
                Module module =  moduleInfo.construct();
                module.overrideComponents(Arqiva.this.project);
                modulesList.add(module);
                return null;
            }

        });

        //Ask modules to configure build components
        for(Module module : modulesList)
            module.configureComponents(this.project);

    }

    private String merge(String content, String container){

        return container.replace("<!--@content-->",content);

    }

    public Project getProject(){

        return project;

    }

    private String adjustAssetsAndTopicsLinks(String html, TopicReference topicReference){

        String backSlashes = "";
        String[] urlTokens = topicReference.getUrl().split("/");
        int totalSlashes = urlTokens.length;

        for(int i=0;i<totalSlashes;i++)
            backSlashes+="../";

        String topicsDirectory = project.getContext().getTopicsDirectory();
        html = html.replace("href=\""+topicsDirectory+"/","href=\""+backSlashes+topicsDirectory+"/");
        html = html.replace("href=\"index.html","href=\""+backSlashes+"index.html");
        html = html.replace("src=\"assets/","src=\""+backSlashes+"assets/");
        return html.replace("href=\"assets/","href=\""+backSlashes+"assets/");

    }


    public String getStartPoint() {
        return startPoint;
    }

    public void setStartPoint(String startPoint) {
        if(startPoint==null)
            throw new IllegalArgumentException("null argument not allowed here");
        this.startPoint = startPoint;
    }

    public Map<String, Object> getBuildHints() {
        return buildHints;
    }

    public void setBuildHints(Map<String, Object> buildHints) {
        if(buildHints==null)
            throw new IllegalArgumentException("null argument not allowed here");
        this.buildHints = buildHints;
    }
}

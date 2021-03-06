/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.mapper;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Filter;
import org.apache.lucene.util.BitDocIdSet;
import org.apache.lucene.util.CloseableThreadLocal;
import org.elasticsearch.ElasticsearchGenerationException;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Preconditions;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.compress.CompressedString;
import org.elasticsearch.common.compress.CompressorFactory;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.text.StringAndBytesText;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.cache.bitset.BitsetFilterCache;
import org.elasticsearch.index.mapper.internal.AllFieldMapper;
import org.elasticsearch.index.mapper.internal.FieldNamesFieldMapper;
import org.elasticsearch.index.mapper.internal.IdFieldMapper;
import org.elasticsearch.index.mapper.internal.IndexFieldMapper;
import org.elasticsearch.index.mapper.internal.ParentFieldMapper;
import org.elasticsearch.index.mapper.internal.RoutingFieldMapper;
import org.elasticsearch.index.mapper.internal.SizeFieldMapper;
import org.elasticsearch.index.mapper.internal.SourceFieldMapper;
import org.elasticsearch.index.mapper.internal.TTLFieldMapper;
import org.elasticsearch.index.mapper.internal.TimestampFieldMapper;
import org.elasticsearch.index.mapper.internal.TypeFieldMapper;
import org.elasticsearch.index.mapper.internal.UidFieldMapper;
import org.elasticsearch.index.mapper.internal.VersionFieldMapper;
import org.elasticsearch.index.mapper.object.ObjectMapper;
import org.elasticsearch.index.mapper.object.RootObjectMapper;
import org.elasticsearch.script.ExecutableScript;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.script.ScriptService.ScriptType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.google.common.collect.Lists.newArrayList;

/**
 *
 */
public class DocumentMapper implements ToXContent {

    /**
     * A result of a merge.
     */
    public static class MergeResult {

        private final String[] conflicts;

        public MergeResult(String[] conflicts) {
            this.conflicts = conflicts;
        }

        /**
         * Does the merge have conflicts or not?
         */
        public boolean hasConflicts() {
            return conflicts.length > 0;
        }

        /**
         * The merge conflicts.
         */
        public String[] conflicts() {
            return this.conflicts;
        }
    }

    public static class MergeFlags {

        public static MergeFlags mergeFlags() {
            return new MergeFlags();
        }

        private boolean simulate = true;

        public MergeFlags() {
        }

        /**
         * A simulation run, don't perform actual modifications to the mapping.
         */
        public boolean simulate() {
            return simulate;
        }

        public MergeFlags simulate(boolean simulate) {
            this.simulate = simulate;
            return this;
        }
    }

    /**
     * A listener to be called during the parse process.
     */
    public static interface ParseListener<ParseContext> {

        public static final ParseListener EMPTY = new ParseListenerAdapter();

        /**
         * Called before a field is added to the document. Return <tt>true</tt> to include
         * it in the document.
         */
        boolean beforeFieldAdded(FieldMapper fieldMapper, Field fieldable, ParseContext parseContent);
    }

    public static class ParseListenerAdapter implements ParseListener {

        @Override
        public boolean beforeFieldAdded(FieldMapper fieldMapper, Field fieldable, Object parseContext) {
            return true;
        }
    }

    public static class Builder {

        private Map<Class<? extends RootMapper>, RootMapper> rootMappers = new LinkedHashMap<>();

        private List<SourceTransform> sourceTransforms;

        private final String index;

        private final Settings indexSettings;

        private final RootObjectMapper rootObjectMapper;

        private ImmutableMap<String, Object> meta = ImmutableMap.of();

        private final Mapper.BuilderContext builderContext;

        public Builder(String index, Settings indexSettings, RootObjectMapper.Builder builder) {
            this.index = index;
            this.indexSettings = indexSettings;
            this.builderContext = new Mapper.BuilderContext(indexSettings, new ContentPath(1));
            this.rootObjectMapper = builder.build(builderContext);

            // UID first so it will be the first stored field to load (so will benefit from "fields: []" early termination
            this.rootMappers.put(UidFieldMapper.class, new UidFieldMapper(indexSettings));
            this.rootMappers.put(IdFieldMapper.class, new IdFieldMapper(indexSettings));
            this.rootMappers.put(RoutingFieldMapper.class, new RoutingFieldMapper(indexSettings));
            // add default mappers, order is important (for example analyzer should come before the rest to set context.analyzer)
            this.rootMappers.put(SizeFieldMapper.class, new SizeFieldMapper(indexSettings));
            this.rootMappers.put(IndexFieldMapper.class, new IndexFieldMapper(indexSettings));
            this.rootMappers.put(SourceFieldMapper.class, new SourceFieldMapper(indexSettings));
            this.rootMappers.put(TypeFieldMapper.class, new TypeFieldMapper(indexSettings));
            this.rootMappers.put(AllFieldMapper.class, new AllFieldMapper(indexSettings));
            this.rootMappers.put(TimestampFieldMapper.class, new TimestampFieldMapper(indexSettings));
            this.rootMappers.put(TTLFieldMapper.class, new TTLFieldMapper(indexSettings));
            this.rootMappers.put(VersionFieldMapper.class, new VersionFieldMapper(indexSettings));
            this.rootMappers.put(ParentFieldMapper.class, new ParentFieldMapper(indexSettings));
            // _field_names last so that it can see all other fields
            this.rootMappers.put(FieldNamesFieldMapper.class, new FieldNamesFieldMapper(indexSettings));
        }

        public Builder meta(ImmutableMap<String, Object> meta) {
            this.meta = meta;
            return this;
        }

        public Builder put(RootMapper.Builder mapper) {
            RootMapper rootMapper = (RootMapper) mapper.build(builderContext);
            rootMappers.put(rootMapper.getClass(), rootMapper);
            return this;
        }

        public Builder transform(ScriptService scriptService, String script, ScriptType scriptType, String language, Map<String, Object> parameters) {
            if (sourceTransforms == null) {
                sourceTransforms = new ArrayList<>();
            }
            sourceTransforms.add(new ScriptTransform(scriptService, script, scriptType, language, parameters));
            return this;
        }

        public DocumentMapper build(DocumentMapperParser docMapperParser) {
            Preconditions.checkNotNull(rootObjectMapper, "Mapper builder must have the root object mapper set");
            return new DocumentMapper(index, indexSettings, docMapperParser, rootObjectMapper, meta, rootMappers, sourceTransforms);
        }
    }


    private CloseableThreadLocal<ParseContext.InternalParseContext> cache = new CloseableThreadLocal<ParseContext.InternalParseContext>() {
        @Override
        protected ParseContext.InternalParseContext initialValue() {
            return new ParseContext.InternalParseContext(index, indexSettings, docMapperParser, DocumentMapper.this, new ContentPath(0));
        }
    };

    private final String index;

    private final Settings indexSettings;

    private final String type;
    private final StringAndBytesText typeText;

    private final DocumentMapperParser docMapperParser;

    private volatile ImmutableMap<String, Object> meta;

    private volatile CompressedString mappingSource;

    private final RootObjectMapper rootObjectMapper;

    private final ImmutableMap<Class<? extends RootMapper>, RootMapper> rootMappers;
    private final RootMapper[] rootMappersOrdered;
    private final RootMapper[] rootMappersNotIncludedInObject;

    private volatile DocumentFieldMappers fieldMappers;

    private volatile ImmutableMap<String, ObjectMapper> objectMappers = ImmutableMap.of();

    private final List<FieldMapperListener> fieldMapperListeners = new CopyOnWriteArrayList<>();

    private final List<ObjectMapperListener> objectMapperListeners = new CopyOnWriteArrayList<>();

    private boolean hasNestedObjects = false;

    private final Filter typeFilter;

    private final Object mappersMutex = new Object();

    private final List<SourceTransform> sourceTransforms;

    public DocumentMapper(String index, @Nullable Settings indexSettings, DocumentMapperParser docMapperParser,
                          RootObjectMapper rootObjectMapper,
                          ImmutableMap<String, Object> meta,
                          Map<Class<? extends RootMapper>, RootMapper> rootMappers, List<SourceTransform> sourceTransforms) {
        this.index = index;
        this.indexSettings = indexSettings;
        this.type = rootObjectMapper.name();
        this.typeText = new StringAndBytesText(this.type);
        this.docMapperParser = docMapperParser;
        this.meta = meta;
        this.rootObjectMapper = rootObjectMapper;
        this.sourceTransforms = sourceTransforms;

        this.rootMappers = ImmutableMap.copyOf(rootMappers);
        this.rootMappersOrdered = rootMappers.values().toArray(new RootMapper[rootMappers.values().size()]);
        List<RootMapper> rootMappersNotIncludedInObjectLst = newArrayList();
        for (RootMapper rootMapper : rootMappersOrdered) {
            if (!rootMapper.includeInObject()) {
                rootMappersNotIncludedInObjectLst.add(rootMapper);
            }
        }
        this.rootMappersNotIncludedInObject = rootMappersNotIncludedInObjectLst.toArray(new RootMapper[rootMappersNotIncludedInObjectLst.size()]);

        this.typeFilter = typeMapper().termFilter(type, null);

        if (rootMapper(ParentFieldMapper.class).active()) {
            // mark the routing field mapper as required
            rootMapper(RoutingFieldMapper.class).markAsRequired();
        }

        FieldMapperListener.Aggregator fieldMappersAgg = new FieldMapperListener.Aggregator();
        for (RootMapper rootMapper : rootMappersOrdered) {
            if (rootMapper.includeInObject()) {
                rootObjectMapper.putMapper(rootMapper);
            } else {
                if (rootMapper instanceof FieldMapper) {
                    fieldMappersAgg.mappers.add((FieldMapper) rootMapper);
                }
            }
        }

        // now traverse and get all the statically defined ones
        rootObjectMapper.traverse(fieldMappersAgg);

        this.fieldMappers = new DocumentFieldMappers(docMapperParser.analysisService).copyAndAllAll(fieldMappersAgg.mappers);

        final Map<String, ObjectMapper> objectMappers = Maps.newHashMap();
        rootObjectMapper.traverse(new ObjectMapperListener() {
            @Override
            public void objectMapper(ObjectMapper objectMapper) {
                objectMappers.put(objectMapper.fullPath(), objectMapper);
            }
        });
        this.objectMappers = ImmutableMap.copyOf(objectMappers);
        for (ObjectMapper objectMapper : objectMappers.values()) {
            if (objectMapper.nested().isNested()) {
                hasNestedObjects = true;
            }
        }

        refreshSource();
    }

    public String type() {
        return this.type;
    }

    public Text typeText() {
        return this.typeText;
    }

    public ImmutableMap<String, Object> meta() {
        return this.meta;
    }

    public CompressedString mappingSource() {
        return this.mappingSource;
    }

    public RootObjectMapper root() {
        return this.rootObjectMapper;
    }

    public UidFieldMapper uidMapper() {
        return rootMapper(UidFieldMapper.class);
    }

    @SuppressWarnings({"unchecked"})
    public <T extends RootMapper> T rootMapper(Class<T> type) {
        return (T) rootMappers.get(type);
    }

    public IndexFieldMapper indexMapper() {
        return rootMapper(IndexFieldMapper.class);
    }

    public TypeFieldMapper typeMapper() {
        return rootMapper(TypeFieldMapper.class);
    }

    public SourceFieldMapper sourceMapper() {
        return rootMapper(SourceFieldMapper.class);
    }

    public AllFieldMapper allFieldMapper() {
        return rootMapper(AllFieldMapper.class);
    }

    public IdFieldMapper idFieldMapper() {
        return rootMapper(IdFieldMapper.class);
    }

    public RoutingFieldMapper routingFieldMapper() {
        return rootMapper(RoutingFieldMapper.class);
    }

    public ParentFieldMapper parentFieldMapper() {
        return rootMapper(ParentFieldMapper.class);
    }

    public SizeFieldMapper sizeFieldMapper() {
        return rootMapper(SizeFieldMapper.class);
    }

    public TimestampFieldMapper timestampFieldMapper() {
        return rootMapper(TimestampFieldMapper.class);
    }

    public TTLFieldMapper TTLFieldMapper() {
        return rootMapper(TTLFieldMapper.class);
    }

    public IndexFieldMapper IndexFieldMapper() {
        return rootMapper(IndexFieldMapper.class);
    }

    public SizeFieldMapper SizeFieldMapper() {
        return rootMapper(SizeFieldMapper.class);
    }

    public Filter typeFilter() {
        return this.typeFilter;
    }

    public boolean hasNestedObjects() {
        return hasNestedObjects;
    }

    public DocumentFieldMappers mappers() {
        return this.fieldMappers;
    }

    public ImmutableMap<String, ObjectMapper> objectMappers() {
        return this.objectMappers;
    }

    public ParsedDocument parse(BytesReference source) throws MapperParsingException {
        return parse(SourceToParse.source(source));
    }

    public ParsedDocument parse(String type, String id, BytesReference source) throws MapperParsingException {
        return parse(SourceToParse.source(source).type(type).id(id));
    }

    public ParsedDocument parse(SourceToParse source) throws MapperParsingException {
        return parse(source, null);
    }

    public ParsedDocument parse(SourceToParse source, @Nullable ParseListener listener) throws MapperParsingException {
        ParseContext.InternalParseContext context = cache.get();

        if (source.type() != null && !source.type().equals(this.type)) {
            throw new MapperParsingException("Type mismatch, provide type [" + source.type() + "] but mapper is of type [" + this.type + "]", context.mappingsModified());
        }
        source.type(this.type);

        XContentParser parser = source.parser();
        try {
            if (parser == null) {
                parser = XContentHelper.createParser(source.source());
            }
            if (sourceTransforms != null) {
                parser = transform(parser);
            }
            context.reset(parser, new ParseContext.Document(), source, listener);

            // will result in START_OBJECT
            int countDownTokens = 0;
            XContentParser.Token token = parser.nextToken();
            if (token != XContentParser.Token.START_OBJECT) {
                throw new MapperParsingException("Malformed content, must start with an object", context.mappingsModified());
            }
            boolean emptyDoc = false;
            token = parser.nextToken();
            if (token == XContentParser.Token.END_OBJECT) {
                // empty doc, we can handle it...
                emptyDoc = true;
            } else if (token != XContentParser.Token.FIELD_NAME) {
                throw new MapperParsingException("Malformed content, after first object, either the type field or the actual properties should exist", context.mappingsModified());
            }

            for (RootMapper rootMapper : rootMappersOrdered) {
                rootMapper.preParse(context);
            }

            if (!emptyDoc) {
                rootObjectMapper.parse(context);
            }

            for (int i = 0; i < countDownTokens; i++) {
                parser.nextToken();
            }

            for (RootMapper rootMapper : rootMappersOrdered) {
                rootMapper.postParse(context);
            }
        } catch (Throwable e) {
            // if its already a mapper parsing exception, no need to wrap it...
            if (e instanceof MapperParsingException) {
                throw (MapperParsingException) e;
            }

            // Throw a more meaningful message if the document is empty.
            if (source.source() != null && source.source().length() == 0) {
                throw new MapperParsingException("failed to parse, document is empty", context.mappingsModified());
            }

            throw new MapperParsingException("failed to parse", e, context.mappingsModified());
        } finally {
            // only close the parser when its not provided externally
            if (source.parser() == null && parser != null) {
                parser.close();
            }
        }
        // reverse the order of docs for nested docs support, parent should be last
        if (context.docs().size() > 1) {
            Collections.reverse(context.docs());
        }
        // apply doc boost
        if (context.docBoost() != 1.0f) {
            Set<String> encounteredFields = Sets.newHashSet();
            for (ParseContext.Document doc : context.docs()) {
                encounteredFields.clear();
                for (IndexableField field : doc) {
                    if (field.fieldType().indexOptions() != IndexOptions.NONE && !field.fieldType().omitNorms()) {
                        if (!encounteredFields.contains(field.name())) {
                            ((Field) field).setBoost(context.docBoost() * field.boost());
                            encounteredFields.add(field.name());
                        }
                    }
                }
            }
        }

        ParsedDocument doc = new ParsedDocument(context.uid(), context.version(), context.id(), context.type(), source.routing(), source.timestamp(), source.ttl(), context.docs(),
                context.source(), context.mappingsModified()).parent(source.parent());
        // reset the context to free up memory
        context.reset(null, null, null, null);
        return doc;
    }

    /**
     * Returns the best nested {@link ObjectMapper} instances that is in the scope of the specified nested docId.
     */
    public ObjectMapper findNestedObjectMapper(int nestedDocId, BitsetFilterCache cache, LeafReaderContext context) throws IOException {
        ObjectMapper nestedObjectMapper = null;
        for (ObjectMapper objectMapper : objectMappers().values()) {
            if (!objectMapper.nested().isNested()) {
                continue;
            }

            BitDocIdSet nestedTypeBitSet = cache.getBitDocIdSetFilter(objectMapper.nestedTypeFilter()).getDocIdSet(context);
            if (nestedTypeBitSet != null && nestedTypeBitSet.bits().get(nestedDocId)) {
                if (nestedObjectMapper == null) {
                    nestedObjectMapper = objectMapper;
                } else {
                    if (nestedObjectMapper.fullPath().length() < objectMapper.fullPath().length()) {
                        nestedObjectMapper = objectMapper;
                    }
                }
            }
        }
        return nestedObjectMapper;
    }

    /**
     * Returns the parent {@link ObjectMapper} instance of the specified object mapper or <code>null</code> if there
     * isn't any.
     */
    // TODO: We should add: ObjectMapper#getParentObjectMapper()
    public ObjectMapper findParentObjectMapper(ObjectMapper objectMapper) {
        int indexOfLastDot = objectMapper.fullPath().lastIndexOf('.');
        if (indexOfLastDot != -1) {
            String parentNestObjectPath = objectMapper.fullPath().substring(0, indexOfLastDot);
            return objectMappers().get(parentNestObjectPath);
        } else {
            return null;
        }
    }

    /**
     * Transform the source when it is expressed as a map.  This is public so it can be transformed the source is loaded.
     * @param sourceAsMap source to transform.  This may be mutated by the script.
     * @return transformed version of transformMe.  This may actually be the same object as sourceAsMap
     */
    public Map<String, Object> transformSourceAsMap(Map<String, Object> sourceAsMap) {
        if (sourceTransforms == null) {
            return sourceAsMap;
        }
        for (SourceTransform transform : sourceTransforms) {
            sourceAsMap = transform.transformSourceAsMap(sourceAsMap);
        }
        return sourceAsMap;
    }

    private XContentParser transform(XContentParser parser) throws IOException {
        Map<String, Object> transformed = transformSourceAsMap(parser.mapOrderedAndClose());
        XContentBuilder builder = XContentFactory.contentBuilder(parser.contentType()).value(transformed);
        return parser.contentType().xContent().createParser(builder.bytes());
    }

    public void addFieldMappers(List<FieldMapper<?>> fieldMappers) {
        synchronized (mappersMutex) {
            this.fieldMappers = this.fieldMappers.copyAndAllAll(fieldMappers);
        }
        for (FieldMapperListener listener : fieldMapperListeners) {
            listener.fieldMappers(fieldMappers);
        }
    }

    public void addFieldMapperListener(FieldMapperListener fieldMapperListener) {
        fieldMapperListeners.add(fieldMapperListener);
    }

    public void traverse(FieldMapperListener listener) {
        for (RootMapper rootMapper : rootMappersOrdered) {
            if (!rootMapper.includeInObject() && rootMapper instanceof FieldMapper) {
                listener.fieldMapper((FieldMapper) rootMapper);
            }
        }
        rootObjectMapper.traverse(listener);
    }

    public void addObjectMappers(Collection<ObjectMapper> objectMappers) {
        addObjectMappers(objectMappers.toArray(new ObjectMapper[objectMappers.size()]));
    }

    private void addObjectMappers(ObjectMapper... objectMappers) {
        synchronized (mappersMutex) {
            MapBuilder<String, ObjectMapper> builder = MapBuilder.newMapBuilder(this.objectMappers);
            for (ObjectMapper objectMapper : objectMappers) {
                builder.put(objectMapper.fullPath(), objectMapper);
                if (objectMapper.nested().isNested()) {
                    hasNestedObjects = true;
                }
            }
            this.objectMappers = builder.immutableMap();
        }
        for (ObjectMapperListener objectMapperListener : objectMapperListeners) {
            objectMapperListener.objectMappers(objectMappers);
        }
    }

    public void addObjectMapperListener(ObjectMapperListener objectMapperListener) {
        objectMapperListeners.add(objectMapperListener);
    }

    public void traverse(ObjectMapperListener listener) {
        rootObjectMapper.traverse(listener);
    }

    public synchronized MergeResult merge(DocumentMapper mergeWith, MergeFlags mergeFlags) {
        MergeContext mergeContext = new MergeContext(this, mergeFlags);
        assert rootMappers.size() == mergeWith.rootMappers.size();

        rootObjectMapper.merge(mergeWith.rootObjectMapper, mergeContext);
        for (Map.Entry<Class<? extends RootMapper>, RootMapper> entry : rootMappers.entrySet()) {
            // root mappers included in root object will get merge in the rootObjectMapper
            if (entry.getValue().includeInObject()) {
                continue;
            }
            RootMapper mergeWithRootMapper = mergeWith.rootMappers.get(entry.getKey());
            if (mergeWithRootMapper != null) {
                entry.getValue().merge(mergeWithRootMapper, mergeContext);
            }
        }

        if (!mergeFlags.simulate()) {
            // let the merge with attributes to override the attributes
            meta = mergeWith.meta();
            // update the source of the merged one
            refreshSource();
        }
        return new MergeResult(mergeContext.buildConflicts());
    }

    public CompressedString refreshSource() throws ElasticsearchGenerationException {
        try {
            BytesStreamOutput bStream = new BytesStreamOutput();
            XContentBuilder builder = XContentFactory.contentBuilder(XContentType.JSON, CompressorFactory.defaultCompressor().streamOutput(bStream));
            builder.startObject();
            toXContent(builder, ToXContent.EMPTY_PARAMS);
            builder.endObject();
            builder.close();
            return mappingSource = new CompressedString(bStream.bytes());
        } catch (Exception e) {
            throw new ElasticsearchGenerationException("failed to serialize source for type [" + type + "]", e);
        }
    }

    public void close() {
        cache.close();
        rootObjectMapper.close();
        for (RootMapper rootMapper : rootMappersOrdered) {
            rootMapper.close();
        }
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        rootObjectMapper.toXContent(builder, params, new ToXContent() {
            @Override
            public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
                if (sourceTransforms != null) {
                    if (sourceTransforms.size() == 1) {
                        builder.field("transform");
                        sourceTransforms.get(0).toXContent(builder, params);
                    } else {
                        builder.startArray("transform");
                        for (SourceTransform transform: sourceTransforms) {
                            transform.toXContent(builder, params);
                        }
                        builder.endArray();
                    }
                }

                if (meta != null && !meta.isEmpty()) {
                    builder.field("_meta", meta());
                }
                return builder;
            }
            // no need to pass here id and boost, since they are added to the root object mapper
            // in the constructor
        }, rootMappersNotIncludedInObject);
        return builder;
    }

    /**
     * Transformations to be applied to the source before indexing and/or after loading.
     */
    private interface SourceTransform extends ToXContent {
        /**
         * Transform the source when it is expressed as a map.  This is public so it can be transformed the source is loaded.
         * @param sourceAsMap source to transform.  This may be mutated by the script.
         * @return transformed version of transformMe.  This may actually be the same object as sourceAsMap
         */
        Map<String, Object> transformSourceAsMap(Map<String, Object> sourceAsMap);
    }

    /**
     * Script based source transformation.
     */
    private static class ScriptTransform implements SourceTransform {
        private final ScriptService scriptService;
        /**
         * Contents of the script to transform the source document before indexing.
         */
        private final String script;
        /**
         * The type of the script to run.
         */
        private final ScriptType scriptType;
        /**
         * Language of the script to transform the source document before indexing.
         */
        private final String language;
        /**
         * Parameters passed to the transform script.
         */
        private final Map<String, Object> parameters;

        public ScriptTransform(ScriptService scriptService, String script, ScriptType scriptType, String language, Map<String, Object> parameters) {
            this.scriptService = scriptService;
            this.script = script;
            this.scriptType = scriptType;
            this.language = language;
            this.parameters = parameters;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> transformSourceAsMap(Map<String, Object> sourceAsMap) {
            try {
                // We use the ctx variable and the _source name to be consistent with the update api.
                ExecutableScript executable = scriptService.executable(language, script, scriptType, ScriptContext.Standard.MAPPING, parameters);
                Map<String, Object> ctx = new HashMap<>(1);
                ctx.put("_source", sourceAsMap);
                executable.setNextVar("ctx", ctx);
                executable.run();
                ctx = (Map<String, Object>) executable.unwrap(ctx);
                return (Map<String, Object>) ctx.get("_source");
            } catch (Exception e) {
                throw new ElasticsearchIllegalArgumentException("failed to execute script", e);
            }
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field("script", script);
            builder.field("lang", language);
            if (parameters != null) {
                builder.field("params", parameters);
            }
            builder.endObject();
            return builder;
        }
    }
}

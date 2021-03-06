/**
 *  Copyright 2012 Sven Ewald
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.xmlbeam;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URISyntaxException;
import java.text.Format;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.xml.bind.annotation.XmlValue;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xmlbeam.annotation.XBDelete;
import org.xmlbeam.annotation.XBDocURL;
import org.xmlbeam.annotation.XBRead;
import org.xmlbeam.annotation.XBValue;
import org.xmlbeam.annotation.XBWrite;
import org.xmlbeam.config.DefaultXMLFactoriesConfig;
import org.xmlbeam.config.XMLFactoriesConfig;
import org.xmlbeam.dom.DOMAccess;
import org.xmlbeam.externalizer.Externalizer;
import org.xmlbeam.externalizer.ExternalizerAdapter;
import org.xmlbeam.io.XBFileIO;
import org.xmlbeam.io.XBStreamInput;
import org.xmlbeam.io.XBStreamOutput;
import org.xmlbeam.io.XBUrlIO;
import org.xmlbeam.types.DefaultTypeConverter;
import org.xmlbeam.types.TypeConverter;
import org.xmlbeam.util.intern.DOMHelper;
import org.xmlbeam.util.intern.ReflectionHelper;

/**
 * <p>
 * Overview<br>
 * The class XMLProjector is a tool to create, read or write so called "projections". Projections
 * are Java interfaces associated to XML documents. Projections may contain methods annotated with
 * XPath selectors. These XPath expressions define the subset of XML data which is "projected" to
 * Java values and objects.
 * </p>
 * <p>
 * Getters<br>
 * For getter methods (methods with a name-prefix "get", returning a value) the XPath-selected nodes
 * are converted to the method return type. This works with all java primitive types, Strings, and
 * lists or arrays containing primitives or Strings.
 * </p>
 * <p>
 * Setters<br>
 * Setter methods (method with a name starting with "set", having a parameter) can be defined to
 * modify the content of the associated XML document. Not all XPath capabilities define writable
 * projections, so the syntax is limited to selectors of elements and attributes. In contrast to
 * Java Beans a setter method may define a return value with the projection interface type. If this
 * return value is defined, the current projection instance is returned. This allows the definition
 * of projections according to the fluent interface pattern (aka Builder Pattern).
 * </p>
 * <p>
 * Sub Projections<br>
 * For the purpose of accessing structured data elements in the XML document you may define
 * "sub projections" which are projections associated to elements instead to documents. Sub
 * projections can be used as return type of getters and as parameters of setters. This works even
 * in arrays or lists. Because of the infamous Java type erasure you have to specify the component
 * type of the sub projection for a getter returning a list of sub projections. This type is defined
 * as second parameter "targetType" in the {@link XBRead} annotation.
 * </p>
 * <p>
 * Dynamic Projections<br>
 * XPath expressions are evaluated during runtime when the corresponding methods are called. Its
 * possible to use placeholder ("{0}, {1}, {2},... ) in the expression that will be substituted with
 * method parameters before the expression is evaluated. Therefore getters and setters may have
 * multiple parameters which will be applied via a {@link MessageFormat} to build up the final XPath
 * expression. The first parameter of a setter will be used for both, setting the document value and
 * replacing the placeholder "{0}".
 * </p>
 * <p>
 * Projection Mixins<br>
 * A mixin is defined as an object implementing a super interface of a projection. You may associate
 * a mixin with a projection type to add your own code to a projection. This way you can implement
 * validators, make a projection comparable or even share common business logic between multiple
 * projections.
 * </p>
 * 
 * @author <a href="https://github.com/SvenEwald">Sven Ewald</a>
 */
@SuppressWarnings("serial")
public class XBProjector implements Serializable, ProjectionFactory {

    private static final Externalizer NOOP_EXTERNALIZER = new ExternalizerAdapter();

    private final ConfigBuilder configBuilder = new ConfigBuilder();

    private Externalizer externalizer = NOOP_EXTERNALIZER;

    private final Set<Flags> flags;

    private class DefaultDOMAccessInvoker implements DOMAccess {
        private final Node documentOrElement;
        private final Class<?> projectionInterface;

        /**
         * @param documentOrElement
         * @param projectionInterface
         */
        private DefaultDOMAccessInvoker(Node documentOrElement, Class<?> projectionInterface) {
            this.documentOrElement = documentOrElement;
            this.projectionInterface = projectionInterface;
        }

        @Override
        public Class<?> getProjectionInterface() {
            return projectionInterface;
        }

        @Override
        public Node getDOMNode() {
            return documentOrElement;
        }

        @Override
        public Document getDOMOwnerDocument() {
            return DOMHelper.getOwnerDocumentFor(documentOrElement);
        }

        @Override
        public Element getDOMBaseElement() {
            if (Node.DOCUMENT_NODE == documentOrElement.getNodeType()) {
                return ((Document) documentOrElement).getDocumentElement();
            }
            assert Node.ELEMENT_NODE == documentOrElement.getNodeType();
            return (Element) documentOrElement;
        }
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof DOMAccess)) {
                return false;
            }
            DOMAccess op = (DOMAccess) o;
            if (!projectionInterface.equals(op.getProjectionInterface())) {
                return false;
            }
            // Unfortunately Node.isEqualNode() is implementation specific and does
            // not need to match our hashCode implementation.
            // So we define our own node equality.
            return DOMHelper.nodesAreEqual(documentOrElement, op.getDOMNode());
        }

        @Override
        public int hashCode() {
            return 31 * projectionInterface.hashCode() + 27 * DOMHelper.nodeHashCode(documentOrElement);
        }

        @Override
        public String asString() {
            try {
                final StringWriter writer = new StringWriter();
                config().createTransformer().transform(new DOMSource(getDOMNode()), new StreamResult(writer));
                final String output = writer.getBuffer().toString();
                return output;
            } catch (TransformerConfigurationException e) {
                throw new RuntimeException(e);
            } catch (TransformerException e) {
                throw new RuntimeException(e);
            }
        }

// /*
// * (non-Javadoc)
// *
// * @see org.xmlbeam.dom.DOMAccess#addNameSpace(java.lang.String, java.lang.String)
// */
// @Override
// public T addNameSpace(String URI, String prefix) {
// getDOMNode().)
// return null;
// }
    }

    private final class DefaultObjectInvoker extends DefaultDOMAccessInvoker {
        private DefaultObjectInvoker(Class<?> projectionInterface, Node documentOrElement) {
            super(documentOrElement, projectionInterface);
        }

        @Override
        public String toString() {
            final String typeDesc = getDOMNode().getNodeType() == Node.DOCUMENT_NODE ? "document '" + getDOMNode().getBaseURI() + "'" : "element " + "'" + getDOMNode().getNodeName() + "[" + Integer.toString(getDOMNode().hashCode(), 16) + "]'";
            return "Projection [" + getProjectionInterface().getName() + "]" + " to " + typeDesc;
        }
    }

    private final class XMLRenderingObjectInvoker extends DefaultDOMAccessInvoker {
        private XMLRenderingObjectInvoker(Class<?> projectionInterface, Node documentOrElement) {
            super(documentOrElement, projectionInterface);
        }

        @Override
        public String toString() {
            return super.asString();
        }
    }
    
    /**
     * A variation of the builder pattern. All methods to configure the projector are hidden in this
     * builder class.
     */
    public class ConfigBuilder implements ProjectionFactoryConfig {

        /**
         * Access the {@link XMLFactoriesConfig} as the given subtype to conveniently access
         * additional methods.
         * 
         * @param clazz
         * @return
         */
        public <T extends XMLFactoriesConfig> T as(Class<T> clazz) {
            return clazz.cast(xMLFactoriesConfig);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public TypeConverter getTypeConverter() {
            return XBProjector.this.typeConverter;
        }
                
        public <T extends TypeConverter> T getTypeConverterAs(Class<T> clazz) {
            return clazz.cast(getTypeConverter());
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public ConfigBuilder setTypeConverter(TypeConverter converter) {
            XBProjector.this.typeConverter = converter;
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public ConfigBuilder setExternalizer(Externalizer e10r) {
            XBProjector.this.externalizer = e10r == null ? NOOP_EXTERNALIZER : e10r;
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Externalizer getExternalizer() {
            return XBProjector.this.externalizer;
        }
        
        /**
         * {@inheritDoc}
         */
        public <T extends Externalizer> T getExternalizerAs(Class<? extends T> clazz) {
            return clazz.cast(getExternalizer());
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public TransformerFactory createTransformerFactory() {
            return XBProjector.this.xMLFactoriesConfig.createTransformerFactory();
        }

        /**
         * {@inheritDoc}
         */

        @Override
        public DocumentBuilderFactory createDocumentBuilderFactory() {
            return XBProjector.this.xMLFactoriesConfig.createDocumentBuilderFactory();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public XPathFactory createXPathFactory() {
            return XBProjector.this.xMLFactoriesConfig.createXPathFactory();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Transformer createTransformer(Document... document) {
            return XBProjector.this.xMLFactoriesConfig.createTransformer(document);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public DocumentBuilder createDocumentBuilder() {
            return XBProjector.this.xMLFactoriesConfig.createDocumentBuilder();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public XPath createXPath(Document... document) {
            return XBProjector.this.xMLFactoriesConfig.createXPath(document);
        }
    }

    /**
     * A variation of the builder pattern. Mixin related methods are grouped behind this builder
     * class.
     */
    public class MixinBuilder implements MixinHolder {
        /**
         * {@inheritDoc}
         */
        @Override
        public <S, M extends S, P extends S> XBProjector addProjectionMixin(Class<P> projectionInterface, M mixinImplementation) {
            ensureIsValidProjectionInterface(projectionInterface);
            Map<Class<?>, Object> map = mixins.containsKey(projectionInterface) ? mixins.get(projectionInterface) : new HashMap<Class<?>, Object>();
            for (Class<?> type : ReflectionHelper.findAllCommonSuperInterfaces(projectionInterface, mixinImplementation.getClass())) {
                map.put(type, mixinImplementation);
            }
            mixins.put(projectionInterface, map);
            return XBProjector.this;

        }

        /**
         * {@inheritDoc}
         */
        @Override
        @SuppressWarnings("unchecked")
        public <S, M extends S, P extends S> M getProjectionMixin(Class<P> projectionInterface, Class<M> mixinInterface) {
            if (!mixins.containsKey(projectionInterface)) {
                return null;
            }
            return (M) mixins.get(projectionInterface).get(mixinInterface);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @SuppressWarnings("unchecked")
        public <S, M extends S, P extends S> M removeProjectionMixin(Class<P> projectionInterface, Class<M> mixinInterface) {
            if (!mixins.containsKey(projectionInterface)) {
                return null;
            }
            return (M) mixins.get(projectionInterface).remove(mixinInterface);
        }
    }

    /**
     * A variation of the builder pattern. IO related methods are grouped behind this builder class.
     */
    public class IOBuilder implements ProjectionIO {

        /**
         * {@inheritDoc}
         */
        @Override
        public XBFileIO file(File file) {
            return new XBFileIO(XBProjector.this, file);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public XBFileIO file(String fileName) {
            return new XBFileIO(XBProjector.this, fileName);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public XBUrlIO url(String url) {
            return new XBUrlIO(XBProjector.this, url);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public XBStreamInput stream(InputStream is) {
            return new XBStreamInput(XBProjector.this, is);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public XBStreamOutput stream(OutputStream os) {
            return new XBStreamOutput(XBProjector.this, os);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public <T> T fromURLAnnotation(final Class<T> projectionInterface, Object... optionalParams) throws IOException {
            org.xmlbeam.annotation.XBDocURL doc = projectionInterface.getAnnotation(org.xmlbeam.annotation.XBDocURL.class);
            if (doc == null) {
                throw new IllegalArgumentException("Class " + projectionInterface.getCanonicalName() + " must have the " + XBDocURL.class.getName() + " annotation linking to the document source.");
            }
            XBUrlIO urlIO = url(MessageFormat.format(doc.value(), optionalParams));
            urlIO.addRequestProperties(filterRequestParamsFromParams(doc.value(), optionalParams));
            return urlIO.read(projectionInterface);
        }

        /**
         * @param projectionInterface
         * @param optionalParams
         * @return
         */
        @SuppressWarnings("unchecked")
        Map<String, String> filterRequestParamsFromParams(final String url, final Object... optionalParams) {
            Map<String, String> requestParams = new HashMap<String, String>();
            Format[] formats = new MessageFormat(url).getFormatsByArgumentIndex();
            for (int i = 0; i < optionalParams.length; ++i) {
                if (i >= formats.length) {
                    if ((optionalParams[i] instanceof Map)) {
                        requestParams.putAll((Map<? extends String, ? extends String>) optionalParams[i]);
                    }
                    continue;
                }
                if (formats[i] == null) {
                    if ((optionalParams[i] instanceof Map)) {
                        requestParams.putAll((Map<? extends String, ? extends String>) optionalParams[i]);
                    }
                }
            }
            return requestParams;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String toURLAnnotationViaPOST(final Object projection, Object... optionalParams) throws IOException, URISyntaxException {
            Class<?> projectionInterface = checkProjectionInstance(projection).getProjectionInterface();
            org.xmlbeam.annotation.XBDocURL doc = projectionInterface.getAnnotation(org.xmlbeam.annotation.XBDocURL.class);
            if (doc == null) {
                throw new IllegalArgumentException("Class " + projectionInterface.getCanonicalName() + " must have the " + XBDocURL.class.getName() + " annotation linking to the document source.");
            }
            XBUrlIO urlIO = url(MessageFormat.format(doc.value(), optionalParams));
            urlIO.addRequestProperties(filterRequestParamsFromParams(doc.value(), optionalParams));
            return urlIO.write(projection);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T projectEmptyDocument(Class<T> projectionInterface) {
        Document document = xMLFactoriesConfig.createDocumentBuilder().newDocument();
        return projectDOMNode(document, projectionInterface);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T projectEmptyElement(final String name, Class<T> projectionInterface) {
        Document document = xMLFactoriesConfig.createDocumentBuilder().newDocument();
        Element element = document.createElement(name);
        return projectDOMNode(element, projectionInterface);
    }

    
//    public <T> T projectProjection(final T projection,String xpath) {
//        if (!( projection instanceof InternalProjection)) {
//            throw new IllegalArgumentException("Given object is not a projection created by a projector.");
//        }
//        DOMAccess domAccess = (DOMAccess) projection;
//        xMLFactoriesConfig.createXPath(domAccess.getDOMOwnerDocument())
//    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T projectDOMNode(final Node documentOrElement, final Class<T> projectionInterface) {
        ensureIsValidProjectionInterface(projectionInterface);

        if (documentOrElement == null) {
            throw new IllegalArgumentException("Parameter node must not be null");
        }

        Map<Class<?>, Object> defaultInvokers = new HashMap<Class<?>, Object>();
        DefaultDOMAccessInvoker invoker;
        if (flags.contains(Flags.TO_STRING_RENDERS_XML)) {
            invoker = new XMLRenderingObjectInvoker(projectionInterface, documentOrElement);
        } else {
            invoker = new DefaultObjectInvoker(projectionInterface, documentOrElement);
        }
        defaultInvokers.put(DOMAccess.class, invoker);
        defaultInvokers.put(Object.class, invoker);
        final ProjectionInvocationHandler projectionInvocationHandler = new ProjectionInvocationHandler(XBProjector.this, documentOrElement, projectionInterface, defaultInvokers);
        if (flags.contains(Flags.SYNCHRONIZE_ON_DOCUMENTS)) {
            final Document document = DOMHelper.getOwnerDocumentFor(documentOrElement);
            InvocationHandler synchronizedInvocationHandler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    synchronized (document) {
                        return projectionInvocationHandler.invoke(proxy, method, args);
                    }
                }
            };
            return ((T) Proxy.newProxyInstance(projectionInterface.getClassLoader(), new Class[] { projectionInterface, InternalProjection.class, Serializable.class }, synchronizedInvocationHandler));
        }
        return ((T) Proxy.newProxyInstance(projectionInterface.getClassLoader(), new Class[] { projectionInterface, InternalProjection.class, Serializable.class }, projectionInvocationHandler));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T projectXMLString(final String xmlContent, final Class<T> projectionInterface) {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(xmlContent.getBytes("utf-8"));
            return new XBStreamInput(this, inputStream).read(projectionInterface);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Marker interface to determine if a Projection instance was created by a Projector. This will
     * be applied automatically to projections.
     */
    interface InternalProjection extends DOMAccess {
    }

    private final XMLFactoriesConfig xMLFactoriesConfig;

    private final Map<Class<?>, Map<Class<?>, Object>> mixins = new HashMap<Class<?>, Map<Class<?>, Object>>();

    private TypeConverter typeConverter = new DefaultTypeConverter();

// private XBProjector(Set<Flags>flags,XMLFactoriesConfig xMLFactoriesConfig) {
// this.xMLFactoriesConfig = xMLFactoriesConfig;
// this.isSynchronizeOnDocuments = flags.contains(Flags.SYNCHRONIZE_ON_DOCUMENTS);
// }

    public enum Flags {
        SYNCHRONIZE_ON_DOCUMENTS, TO_STRING_RENDERS_XML, OMIT_EMPTY_NODES
    }

    /**
     * Constructor. Use me to create a projector with defaults.
     */
    public XBProjector(Flags... optionalFlags) {
        this(new DefaultXMLFactoriesConfig(), optionalFlags);
    }

    private static <T extends Enum<T>> Set<T> unfold(T[] array) {
        if (array == null || array.length == 0) {
            return Collections.emptySet();
        }
        EnumSet<T> enumSet = EnumSet.of(array[0]);
        for (int i = 1; i < array.length; ++i) {
            enumSet.add(array[i]);
        }
        return enumSet;

    }

    /**
     * @param xMLFactoriesConfig
     */
    public XBProjector(XMLFactoriesConfig xMLFactoriesConfig, Flags... flags) {
        this.xMLFactoriesConfig = xMLFactoriesConfig;
        // isSynchronizeOnDocuments = false;
        this.flags = unfold(flags);
    }

    /**
     * Shortcut for creating a {@link ConfigBuilder} object to change the projectors configuration.
     * 
     * @return a new ConfigBuilder for this projector.
     */
    public ConfigBuilder config() {
        return configBuilder;
    }

    /**
     * Shortcut for creating a {@link MixinBuilder} object add or remove mixins to projections.
     * 
     * @return a new MixinBuilder for this projector.
     */
    public MixinBuilder mixins() {
        return new MixinBuilder();
    }

    /**
     * Ensures that the given object is a projection created by a projector.
     * 
     * @param projection
     * @return
     */
    private InternalProjection checkProjectionInstance(Object projection) {
        if (!(projection instanceof InternalProjection)) {
            throw new IllegalArgumentException("Given object " + projection + " is not a projection.");
        }
        return (InternalProjection) projection;
    }

    /**
     * @param projectionInterface
     * @return true if param is a public interface.
     */
    private void ensureIsValidProjectionInterface(final Class<?> projectionInterface) {
        if  ((projectionInterface == null) || 
            (!projectionInterface.isInterface()) ||
            ((projectionInterface.getModifiers() & Modifier.PUBLIC) != Modifier.PUBLIC)) {
            throw new IllegalArgumentException("Parameter " + projectionInterface + " is not a public interface.");
        }
        if (projectionInterface.isAnnotation()) {
            throw new IllegalArgumentException("Parameter " + projectionInterface + " is an annotation interface. Remove the @ and try again.");
        }
        for (Method method : projectionInterface.getMethods()) {
            boolean isRead=(method.getAnnotation(XBRead.class)!=null);
            boolean isWrite=(method.getAnnotation(XBWrite.class)!=null);
            boolean isDelete=(method.getAnnotation(XBDelete.class)!=null);
            if (isRead ? isWrite || isDelete : isWrite && isDelete) {
                throw new IllegalArgumentException("Method "+method+" has to many annotations. Decide for one of @"+XBRead.class.getSimpleName()+", @"+XBWrite.class.getSimpleName()+", or @"+XBDelete.class.getSimpleName());
            }
            if (isRead) {
                if (!ReflectionHelper.hasReturnType(method)) {
                    throw new IllegalArgumentException("Method "+method+" has @"+XBRead.class.getSimpleName()+" annotation, but has no return type,");
                }
            }
            if (isWrite) {
                if (!ReflectionHelper.hasParameters(method)) {
                    throw new IllegalArgumentException("Method "+method+" has @"+XBWrite.class.getSimpleName()+" annotaion, but has no paramerter");
                }                
            }
            int count=0;
            for (Annotation[] paramAnnotations:method.getParameterAnnotations()){
                for (Annotation a:paramAnnotations) {
                    if (XBValue.class.equals(a.annotationType())) {
                        if (!isWrite) {
                            throw new IllegalArgumentException("Method "+method+" is not a writing projection method, but has an @"+XBValue.class.getSimpleName()+" annotaion.");
                        }
                        if (count>0) {
                            throw new IllegalArgumentException("Method "+method+" has multiple @"+XBValue.class.getSimpleName()+" annotaions.");
                        }
                        ++count;                            
                    }
                }
            }
        }
       
    }

    /**
     * Access to the input/output features of this projector.
     * 
     * @return A new IOBuilder providing methods to read or write projections.
     */
    @Override
    public IOBuilder io() {
        return new IOBuilder();
    }

    /**
     * @param emptyProjection
     * @return
     */
    @Override
    public String asString(Object projection) {
        if (!(projection instanceof InternalProjection)) {
            throw new IllegalArgumentException("Argument is not a projection.");
        }
        final DOMAccess domAccess = (DOMAccess) projection;
        return domAccess.asString();
    }
}

package com.lts.core.support.bean;

import com.lts.core.commons.utils.ClassHelper;

import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Robert HG (254963746@qq.com) on 4/2/16.
 */
public class JdkCompiler {

    public static final String CLASS_EXTENSION = ".class";
    public static final String JAVA_EXTENSION = ".java";

    private final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

    private final DiagnosticCollector<JavaFileObject> diagnosticCollector = new DiagnosticCollector<JavaFileObject>();

    private final ClassLoaderImpl classLoader;

    private final JavaFileManagerImpl javaFileManager;

    private volatile List<String> options;

    public JdkCompiler() {

        options = new ArrayList<String>();
//        options.add("-target");
//        options.add("1.6");
        StandardJavaFileManager manager = compiler.getStandardFileManager(diagnosticCollector, null, null);
        final ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader instanceof URLClassLoader
                && (!loader.getClass().getName().equals("sun.misc.Launcher$AppClassLoader"))) {
            try {
                URLClassLoader urlClassLoader = (URLClassLoader) loader;
                List<File> files = new ArrayList<File>();
                for (URL url : urlClassLoader.getURLs()) {
                    files.add(new File(url.getFile()));
                }
                manager.setLocation(StandardLocation.CLASS_PATH, files);
            } catch (IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
        classLoader = AccessController.doPrivileged(new PrivilegedAction<ClassLoaderImpl>() {
            public ClassLoaderImpl run() {
                return new ClassLoaderImpl(loader);
            }
        });
        javaFileManager = new JavaFileManagerImpl(manager, classLoader);
    }

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("package\\s+([$_a-zA-Z][$_a-zA-Z0-9\\.]*);");

    private static final Pattern CLASS_PATTERN = Pattern.compile("class\\s+([$_a-zA-Z][$_a-zA-Z0-9]*)\\s+");

    public Class<?> compile(String code, ClassLoader classLoader) {
        code = code.trim();
        Matcher matcher = PACKAGE_PATTERN.matcher(code);
        String pkg;
        if (matcher.find()) {
            pkg = matcher.group(1);
        } else {
            pkg = "";
        }
        matcher = CLASS_PATTERN.matcher(code);
        String cls;
        if (matcher.find()) {
            cls = matcher.group(1);
        } else {
            throw new IllegalArgumentException("No such class name in " + code);
        }
        String className = pkg != null && pkg.length() > 0 ? pkg + "." + cls : cls;
        try {
            return Class.forName(className, true, ClassHelper.getCallerClassLoader(getClass()));
        } catch (ClassNotFoundException e) {
            if (!code.endsWith("}")) {
                throw new IllegalStateException("The java code not endsWith \"}\", code: \n" + code + "\n");
            }
            try {
                return doCompile(className, code);
            } catch (RuntimeException t) {
                throw t;
            } catch (Throwable t) {
                throw new IllegalStateException("Failed to compile class, cause: " + t.getMessage() + ", class: " + className + ", code: \n" + code + "\n", t);
            }
        }
    }

    public Class<?> doCompile(String name, String sourceCode) throws Throwable {
        int i = name.lastIndexOf('.');
        String packageName = i < 0 ? "" : name.substring(0, i);
        String className = i < 0 ? name : name.substring(i + 1);
        JavaFileObjectImpl javaFileObject = new JavaFileObjectImpl(className, sourceCode);
        javaFileManager.putFileForInput(StandardLocation.SOURCE_PATH, packageName,
                className + JAVA_EXTENSION, javaFileObject);
        Boolean result = compiler.getTask(null, javaFileManager, diagnosticCollector, options,
                null, Collections.singletonList(javaFileObject)).call();
        if (result == null || !result) {
            throw new IllegalStateException("Compilation failed. class: " + name + ", diagnostics: " + diagnosticCollector);
        }
        return classLoader.loadClass(name);
    }

    private final class ClassLoaderImpl extends ClassLoader {

        private final Map<String, JavaFileObject> classes = new HashMap<String, JavaFileObject>();

        ClassLoaderImpl(final ClassLoader parentClassLoader) {
            super(parentClassLoader);
        }

        Collection<JavaFileObject> files() {
            return Collections.unmodifiableCollection(classes.values());
        }

        @Override
        protected Class<?> findClass(final String qualifiedClassName) throws ClassNotFoundException {
            JavaFileObject file = classes.get(qualifiedClassName);
            if (file != null) {
                byte[] bytes = ((JavaFileObjectImpl) file).getByteCode();
                return defineClass(qualifiedClassName, bytes, 0, bytes.length);
            }
            try {
                return ClassHelper.forNameWithCallerClassLoader(qualifiedClassName, getClass());
            } catch (ClassNotFoundException nf) {
                return super.findClass(qualifiedClassName);
            }
        }

        void add(final String qualifiedClassName, final JavaFileObject javaFile) {
            classes.put(qualifiedClassName, javaFile);
        }

        @Override
        protected synchronized Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            return super.loadClass(name, resolve);
        }

        @Override
        public InputStream getResourceAsStream(final String name) {
            if (name.endsWith(CLASS_EXTENSION)) {
                String qualifiedClassName = name.substring(0, name.length() - CLASS_EXTENSION.length()).replace('/', '.');
                JavaFileObjectImpl file = (JavaFileObjectImpl) classes.get(qualifiedClassName);
                if (file != null) {
                    return new ByteArrayInputStream(file.getByteCode());
                }
            }
            return super.getResourceAsStream(name);
        }
    }

    private static final class JavaFileObjectImpl extends SimpleJavaFileObject {

        private ByteArrayOutputStream bytecode;

        private final CharSequence source;

        public JavaFileObjectImpl(final String baseName, final CharSequence source) {
            super(toURI(baseName + JAVA_EXTENSION), Kind.SOURCE);
            this.source = source;
        }

        JavaFileObjectImpl(final String name, final Kind kind) {
            super(toURI(name), kind);
            source = null;
        }

        public JavaFileObjectImpl(URI uri, Kind kind) {
            super(uri, kind);
            source = null;
        }

        @Override
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) throws UnsupportedOperationException {
            if (source == null) {
                throw new UnsupportedOperationException("source == null");
            }
            return source;
        }

        @Override
        public InputStream openInputStream() {
            return new ByteArrayInputStream(getByteCode());
        }

        @Override
        public OutputStream openOutputStream() {
            return bytecode = new ByteArrayOutputStream();
        }

        public byte[] getByteCode() {
            return bytecode.toByteArray();
        }
    }

    private static final class JavaFileManagerImpl extends ForwardingJavaFileManager<JavaFileManager> {

        private final ClassLoaderImpl classLoader;

        private final Map<URI, JavaFileObject> fileObjects = new HashMap<URI, JavaFileObject>();

        public JavaFileManagerImpl(JavaFileManager fileManager, ClassLoaderImpl classLoader) {
            super(fileManager);
            this.classLoader = classLoader;
        }

        @Override
        public FileObject getFileForInput(Location location, String packageName, String relativeName) throws IOException {
            FileObject o = fileObjects.get(uri(location, packageName, relativeName));
            if (o != null)
                return o;
            return super.getFileForInput(location, packageName, relativeName);
        }

        public void putFileForInput(StandardLocation location, String packageName, String relativeName, JavaFileObject file) {
            fileObjects.put(uri(location, packageName, relativeName), file);
        }

        private URI uri(Location location, String packageName, String relativeName) {
            return toURI(location.getName() + '/' + packageName + '/' + relativeName);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String qualifiedName, JavaFileObject.Kind kind, FileObject outputFile)
                throws IOException {
            JavaFileObject file = new JavaFileObjectImpl(qualifiedName, kind);
            classLoader.add(qualifiedName, file);
            return file;
        }

        @Override
        public ClassLoader getClassLoader(JavaFileManager.Location location) {
            return classLoader;
        }

        @Override
        public String inferBinaryName(Location loc, JavaFileObject file) {
            if (file instanceof JavaFileObjectImpl)
                return file.getName();
            return super.inferBinaryName(loc, file);
        }

        @Override
        public Iterable<JavaFileObject> list(Location location, String packageName, Set<JavaFileObject.Kind> kinds, boolean recurse)
                throws IOException {
            Iterable<JavaFileObject> result = super.list(location, packageName, kinds, recurse);

            ArrayList<JavaFileObject> files = new ArrayList<JavaFileObject>();

            if (location == StandardLocation.CLASS_PATH && kinds.contains(JavaFileObject.Kind.CLASS)) {
                for (JavaFileObject file : fileObjects.values()) {
                    if (file.getKind() == JavaFileObject.Kind.CLASS && file.getName().startsWith(packageName)) {
                        files.add(file);
                    }
                }

                files.addAll(classLoader.files());
            } else if (location == StandardLocation.SOURCE_PATH && kinds.contains(JavaFileObject.Kind.SOURCE)) {
                for (JavaFileObject file : fileObjects.values()) {
                    if (file.getKind() == JavaFileObject.Kind.SOURCE && file.getName().startsWith(packageName)) {
                        files.add(file);
                    }
                }
            }

            for (JavaFileObject file : result) {
                files.add(file);
            }

            return files;
        }
    }

    private static URI toURI(String name) {
        try {
            return new URI(name);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

}

/**
 * Copyright (C) 2010 ZeroTurnaround OU
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v2 as published by
 * the Free Software Foundation, with the additional requirement that
 * ZeroTurnaround OU must be prominently attributed in the program.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You can find a copy of GNU General Public License v2 from
 *   http://www.gnu.org/licenses/gpl-2.0.txt
 */

package com.danhaywood.isis.tool.jrebelplugin;

import java.util.HashMap;
import java.util.Map;

import org.datanucleus.ClassLoaderResolverImpl;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.MetaDataManager;
import org.zeroturnaround.bundled.javassist.ByteArrayClassPath;
import org.zeroturnaround.bundled.javassist.ClassPool;
import org.zeroturnaround.bundled.javassist.CtClass;
import org.zeroturnaround.bundled.javassist.LoaderClassPath;
import org.zeroturnaround.bundled.javassist.NotFoundException;
import org.zeroturnaround.javarebel.ClassBytecodeProcessor;
import org.zeroturnaround.javarebel.ClassEventListener;
import org.zeroturnaround.javarebel.ClassResourceSource;
import org.zeroturnaround.javarebel.Integration;
import org.zeroturnaround.javarebel.IntegrationFactory;
import org.zeroturnaround.javarebel.LoggerFactory;
import org.zeroturnaround.javarebel.Plugin;
import org.zeroturnaround.javarebel.ReloaderFactory;

import org.apache.isis.objectstore.jdo.datanucleus.DataNucleusApplicationComponents;

public class IsisJRebelPlugin implements Plugin {

    private final Map<String, byte[]> bytecodeByClassName = new HashMap<String, byte[]>();

    private String packagePrefix;

    private boolean metDependencies = false;

    public boolean checkDependencies(ClassLoader classLoader, ClassResourceSource classResourceSource) {

        if (metDependencies) {
            return metDependencies;
        }

        packagePrefix = System.getProperty("isis-jrebel-plugin.packagePrefix");
        if (packagePrefix == null) {
            log("*****************************************************************");
            log("*");
            log("* Isis JRebel Plugin is ***DISABLED***");
            log("*");
            log("* specify package prefix through system property, eg:");
            log("*   -Disis-jrebel-plugin.packagePrefix=com.mycompany.myapp");
            log("*");
            log("*****************************************************************");
            return false;
        }

        if (classResourceSource.getClassResource("org.apache.isis.core.runtime.system.context.IsisContext") == null) {
            log("Isis JRebel Plugin ignored, Isis framework classes not found");
            return false;
        }

        log("*****************************************************************");
        log("*");
        log("* Isis JRebel Plugin is ENABLED");
        log("*");
        log("* reloading classes under " + packagePrefix);
        log("*");
        log("*****************************************************************");
        return (metDependencies = true);
    }

    public void preinit() {

        // necessary to do again (as well as in checkDependencies) because
        // JRebel seems to instantiate the plugin twice, once to do the check,
        // second to actually initialize.
        packagePrefix = System.getProperty("isis-jrebel-plugin.packagePrefix");

        Integration i = IntegrationFactory.getInstance();
        ClassLoader cl = IsisJRebelPlugin.class.getClassLoader();

        i.addIntegrationProcessor(cl, newIntegrationProcessor());

        ReloaderFactory.getInstance().addClassLoadListener(newClassLoadListener());
        ReloaderFactory.getInstance().addClassReloadListener(newClassReloadListener());

    }

    // prevent infinite loop
    boolean processing = false;
    // ensure single-threaded
    private Object threadSafety = new Object();

    private ClassBytecodeProcessor newIntegrationProcessor() {
        return new ClassBytecodeProcessor() {

            public byte[] process(ClassLoader cl, String className, byte[] bytecode) {
                synchronized (threadSafety) {
                    if (processing) {
                        return bytecode;
                    }
                    processing = true;
                    try {
                        return processSafely(cl, className, bytecode);
                    } catch (Throwable e) {
                        e.printStackTrace(System.err);
                        return bytecode;
                    } finally {
                        processing = false;
                    }
                }
            }

            private byte[] processSafely(ClassLoader cl, String className, byte[] bytecode) throws NotFoundException, ClassNotFoundException {
                className = className.replace('/', '.');

                if (!underPackage(className)) {
                    return bytecode;
                }

                log("processing: " + className);

                ClassPool cp = new ClassPool();
                cp.appendClassPath(new RebelClassPath());
                cp.appendClassPath(new ByteArrayClassPath(className, bytecode));
                cp.appendSystemPath();
                cp.appendClassPath(new LoaderClassPath(cl));
                CtClass ctClass = cp.get(className);
                ctClass.defrost();

                log("  annotations:");
                Object[] annotations = ctClass.getAnnotations();
                boolean persistenceCapable = false;
                for (Object annotation : annotations) {
                    log("  - " + annotation);
                    if (annotation.toString().contains("@javax.jdo.annotations.PersistenceCapable")) {
                        persistenceCapable = true;
                    }
                }

                if (!persistenceCapable) {
                    log("  not persistence-capable entity, skipping");
                    return bytecode;
                }

                // figure out if this bytecode has been enhanced
                log("  determining whether bytecode has been enhanced...");
                CtClass[] interfaces = ctClass.getInterfaces();
                boolean enhanced = false;
                log("    implements interfaces:");
                if (interfaces != null) {
                    for (CtClass ifc : interfaces) {
                        log("    - " + ifc.getName());
                        if ("javax.jdo.spi.PersistenceCapable".equals(ifc.getName())) {
                            enhanced = true;
                        }
                    }
                }

                if (!enhanced) {
                    log("    not enhanced");
                } else {
                    log("    enhanced");
                }

                
                // enhance ...
                if (!enhanced) {

                    // ignore any unenhanced bytecode, and just use
                    // the previous (enhanced) bytecode previous seen.
                    // we expect the IDE's enhancer plugin to provide a further class load with
                    // new enhanced bytes.
                    //
                    // in testing, found that the call with unenhanced bytes often seemed to happen immediately
                    // that the Eclipse compiler finished its compilation (ie eagerly), and that the follow-up load with
                    // enhanced bytes occurred when the object was interacted with (ie lazily).  So, depending on
                    // user action, there could be several seconds (even minutes) gap between the two calls.
                    log("  using previous (enhanced) bytecode");
                    bytecode = bytecodeByClassName.get(className);
                    
                } else {

                    // the bytecode we have represents an enhanced class, so cache it 
                    // so can use it in future if this class is ever reloaded in an unenhanced form
                    // (ie the other branch of this if statement)
                    bytecodeByClassName.put(className, bytecode);

                    discardJdoMetadata(className, bytecode);
                }

                return bytecode;
            }

            // we invalidate the metadata for the remainder of this call, then
            // tell Isis to recreate the PMF lazily next time.
            // (as good as we can do?)
            private void discardJdoMetadata(String className, byte[] bytecode) throws ClassNotFoundException {

                log("  loading bytecode into separate classloader ");
                ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
                CustomClassLoader ccl = new CustomClassLoader(systemClassLoader);

                ccl.defineClass(className, bytecode);
                ccl.loadClass(className);

                log("  discarding existing JDO metadata: " + className);
                MetaDataManager metaDataManager = DataNucleusApplicationComponents.getMetaDataManager();
                if (metaDataManager != null) {
                    ClassLoaderResolverImpl clr = new ClassLoaderResolverImpl(ccl);
                    AbstractClassMetaData existingMetadata = metaDataManager.getMetaDataForClass(className, clr);
                    
                    if (existingMetadata == null) {
                        log("    no existing metadata to unload");
                    } else {
                        log("    unloading metadata");
                        try {
                            metaDataManager.unloadMetaDataForClass(className);
                        } catch(Exception ignore) {
                            // sometimes get a LinkageError here, some sort of race condition?
                            // have decided not to care about it, since we recreate the PMF anyway next time round
                        }
                    }
                } else {
                    log("    DataNucleus not yet instantiated, so skipping");
                }


                log("  forcing PMF to recreate next time");
                DataNucleusApplicationComponents.markAsStale();
            }
        };
    }

    private ClassEventListener newClassLoadListener() {
        return new ClassEventListener() {

            @SuppressWarnings("rawtypes")
            public void onClassEvent(int eventType, Class klass) {
                updateIsisMetadata("loading: ", klass);
            }

            public int priority() {
                return 0;
            }
        };
    }

    private ClassEventListener newClassReloadListener() {
        return new ClassEventListener() {
            @SuppressWarnings("rawtypes")
            public void onClassEvent(int eventType, Class klass) {
                updateIsisMetadata("reloading: ", klass);
            }

            public int priority() {
                return 0;
            }
        };
    }

    @SuppressWarnings("rawtypes")
    private void updateIsisMetadata(String msg, Class klass) {

        final String className = klass.getName();
        if (!underPackage(className)) {
            return;
        }

        log(msg + klass.getName());

        log("  removing Isis metadata: " + className);
        if (org.apache.isis.core.runtime.system.context.IsisContext.exists()) {
            org.apache.isis.core.runtime.system.context.IsisContext.getSpecificationLoader().invalidateCache(klass);
        } else {
            log("    skipping, Isis metamodel not yet available");
        }

    }

    private boolean underPackage(String className) {
        return packagePrefix != null && className.startsWith(packagePrefix);
    }

    private static void log(String msg) {
        LoggerFactory.getInstance().log(msg);
        System.err.println(msg);
    }

    public String getId() {
        return "isis-jrebel-plugin";
    }

    public String getName() {
        return "Isis JRebel Plugin";
    }

    public String getDescription() {
        return "Reload Isis and JDO metadata";
    }

    public String getAuthor() {
        return null;
    }

    public String getWebsite() {
        return null;
    }

    public String getSupportedVersions() {
        return null;
    }

    public String getTestedVersions() {
        return null;
    }
}

package com.antfortune.freeline

import groovy.io.FileType
import groovy.json.JsonBuilder
import groovy.xml.XmlUtil
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.util.VersionNumber

/**
 * Created by yeqi on 16/5/3.
 */
class FreelinePlugin implements Plugin<Project> {

    String freelineVersion = "0.7.2"

    @Override
    void apply(Project project) {

        project.extensions.create("freeline", FreelineExtension, project)

        if (FreelineUtils.getProperty(project, "disableAutoDependency")) {
            println "freeline auto-dependency disabled"
        } else {
            println "freeline auto add runtime dependencies: ${freelineVersion}"
            project.dependencies {
                debugCompile "com.antfortune.freeline:runtime:${freelineVersion}"
                releaseCompile "com.antfortune.freeline:runtime-no-op:${freelineVersion}"
                testCompile "com.antfortune.freeline:runtime-no-op:${freelineVersion}"
            }
        }

        project.rootProject.task("initFreeline") << {
            FreelineInitializer.initFreeline(project)
        }

        project.rootProject.task("checkBeforeCleanBuild") << {
            FreelineInitializer.generateProjectDescription(project)
        }

        project.afterEvaluate {
            // check
            if (!project.plugins.hasPlugin("com.android.application")) {
                throw new RuntimeException("Freeline plugin can only be applied for android application module.")
            }
            
            project.android.applicationVariants.each { variant ->
                def extension = project.extensions.findByName("freeline") as FreelineExtension
                def productFlavor = extension.productFlavor
                def apkPath = extension.apkPath
                def excludeHackClasses = extension.excludeHackClasses
                def forceLowerVersion = extension.foceLowerVersion
                def applicationProxy = extension.applicationProxy
                def aptEnabled = extension.aptEnabled
                def retrolambdaEnabled = extension.retrolambdaEnabled
                def freelineBuild = FreelineUtils.getProperty(project, "freelineBuild");

                if (!"debug".equalsIgnoreCase(variant.buildType.name as String)) {
                    println "variant ${variant.name} is not debug, skip hack process."
                    return
                } else if (!FreelineUtils.isEmpty(productFlavor) && !productFlavor.toString().equalsIgnoreCase(variant.flavorName)) {
                    println "variant ${variant.name} is not ${productFlavor}, skip hack process."
                    return
                }

                println "find variant ${variant.name} start hack process..."

                if (!freelineBuild) {
                    return
                }

                if (isProguardEnable(project, variant)) {
                    throw new RuntimeException("Freeline doesn't support proguard now, please disable proguard config then re-run freeline.")
                }

                // find the default apk
                if (FreelineUtils.isEmpty(apkPath)) {
                    def apk_paths = []
                    variant.outputs.each { output ->
                        def path = output.outputFile.absolutePath
                        if (path.endsWith(".apk")) {
                            apk_paths.add(output.outputFile.absolutePath)
                        }
                    }
                    def defaultApkPath = FreelineUtils.getDefaultApkPath(apk_paths, project.buildDir.absolutePath, project.name, productFlavor)
                    println "find default apk path: ${defaultApkPath}"
                    FreelineUtils.addNewAttribute(project, 'apk_path', defaultApkPath)
                }

                // add addtional aapt args
                def publicKeeperGenPath = FreelineUtils.joinPath(FreelineUtils.getBuildCacheDir(project.buildDir.absolutePath), "public_keeper.xml")
                project.android.aaptOptions.additionalParameters("-P", publicKeeperGenPath)
                println "Freeline add additionalParameters `-P ${publicKeeperGenPath}` to aaptOptions"

                // add generate task
                FreelineConfigGenerateTask generateTask = project.tasks.create("generate${variant.name.capitalize()}FreelineConfig", FreelineConfigGenerateTask)
                def freelineGenerateOutputDir = new File("$project.buildDir/generated/freeline")
                def manifestPath = project.android.sourceSets.main.manifest.srcFile.path
                generateTask.packageName = FreelineParser.getPackageName(project.android.defaultConfig.applicationId.toString(), manifestPath)
                generateTask.applicationClass = FreelineParser.getApplication(manifestPath, generateTask.packageName)
                generateTask.outputDir = freelineGenerateOutputDir
                variant.registerJavaGeneratingTask(generateTask, freelineGenerateOutputDir)

                // force tasks to run
                def mergeAssetsTask = project.tasks.findByName("merge${variant.name.capitalize()}Assets")
                mergeAssetsTask.outputs.upToDateWhen { false }

                if (applicationProxy) {
                    variant.outputs.each { output ->
                        output.processManifest.outputs.upToDateWhen { false }
                        output.processManifest.doLast {
                            def manifestOutFile = output.processManifest.manifestOutputFile
                            if (manifestOutFile.exists()) {
                                println "find manifest file path: ${manifestOutFile.absolutePath}"
                                replaceApplication(manifestOutFile.absolutePath as String)
                            }
                        }
                    }
                }

                // add freeline generated files to assets
                mergeAssetsTask.doLast {
                    addFreelineGeneratedFiles(project, new File(FreelineGenerator.generateProjectBuildAssetsPath(project.buildDir.absolutePath, productFlavor)), null)
                }

                // find thrid party libraries' resources dependencies
                project.rootProject.allprojects.each { p ->
                    findResourceDependencies(variant, p, FreelineUtils.getBuildCacheDir(project.buildDir.absolutePath))
                }

                def addtionalJars = []
                def projectAptConfig = [:]
                def projectRetrolambdaConfig = [:]

                project.rootProject.allprojects { pro ->
                    if (pro.plugins.hasPlugin("com.android.application") || pro.plugins.hasPlugin("com.android.library")) {
                        // find additional jars
                        if (pro.android.hasProperty("libraryRequests")) {
                            pro.android.libraryRequests.each { p ->
                                def jar_path = FreelineUtils.joinPath(
                                        pro.android.sdkDirectory.toString(),
                                        'platforms',
                                        pro.android.compileSdkVersion.toString(),
                                        'optional',
                                        p.name + ".jar")
                                def f = new File(jar_path)
                                if (f.exists() && f.isFile()) {
                                    addtionalJars.add(jar_path)
                                    println "find additional jar: ${jar_path}"
                                }
                            }
                        }

                        // find apt config
                        def isAptEnabled = pro.plugins.hasPlugin("android-apt")

                        def javaCompile
                        if (pro.plugins.hasPlugin("com.android.application")) {
                            javaCompile = variant.hasProperty('javaCompiler') ? variant.javaCompiler : variant.javaCompile
                        } else {
                            pro.android.libraryVariants.each { libraryVariant ->
                                if ("release".equalsIgnoreCase(libraryVariant.buildType.name as String)) {
                                    javaCompile = libraryVariant.hasProperty('javaCompiler') ? libraryVariant.javaCompiler : libraryVariant.javaCompile
                                    return false
                                }
                            }
                        }

                        if (aptEnabled && isAptEnabled && javaCompile) {
                            println "Freeline found ${pro.name} apt plugin enabled."
                            javaCompile.outputs.upToDateWhen { false }
                            javaCompile.doFirst {
                                def aptConfiguration = pro.configurations.findByName("apt")
                                if (aptConfiguration && !aptConfiguration.empty) {
                                    def aptOutputDir
                                    if (pro.plugins.hasPlugin("com.android.application")) {
                                        aptOutputDir = new File(pro.buildDir, "generated/source/apt/${variant.dirName}").absolutePath
                                    } else {
                                        aptOutputDir = new File(pro.buildDir, "generated/source/apt/release").absolutePath
                                    }
                                    def processorPath = (aptConfiguration + javaCompile.classpath).asPath

                                    boolean disableDiscovery = javaCompile.options.compilerArgs.indexOf('-processorpath') == -1

                                    int processorIndex = javaCompile.options.compilerArgs.indexOf('-processor')
                                    def processor = null
                                    if (processorIndex != -1) {
                                        processor = javaCompile.options.compilerArgs.get(processorIndex + 1)
                                    }

                                    def aptArgs = []
                                    javaCompile.options.compilerArgs.each { arg ->
                                        if (arg.toString().startsWith('-A')) {
                                            aptArgs.add(arg)
                                        }
                                    }

                                    def aptConfig = ['enabled': isAptEnabled, 'disableDiscovery': disableDiscovery, 'aptOutput': aptOutputDir, 'processorPath': processorPath, 'processor': processor, 'aptArgs': aptArgs]
                                    projectAptConfig[pro.name] = aptConfig
                                    println("$pro.name APT config:")
                                    println(aptConfig)
                                }
                            }
                        }
                    }

                    // find retrolambda config
                    if (retrolambdaEnabled && pro.plugins.hasPlugin("me.tatarka.retrolambda")) {
                        def jdk8 = getJdk8()
                        if (jdk8 != null) {
                            def rtJar = "${jdk8}/jre/lib/rt.jar"
                            def retrolambdaConfig = pro.configurations.getByName("retrolambdaConfig")
                            def targetJar = pro.files(retrolambdaConfig).asPath
                            def mainClass = 'net.orfjackal.retrolambda.Main'

                            VersionNumber retrolambdaVersion = retrolambdaVersion(retrolambdaConfig)
                            def supportIncludeFiles = requireVersion(retrolambdaVersion, '2.1.0')

                            def lambdaConfig = [
                                    'enabled': retrolambdaEnabled,
                                    'targetJar': targetJar,
                                    'mainClass': mainClass,
                                    'rtJar': rtJar,
                                    'supportIncludeFiles': supportIncludeFiles
                            ]
                            println("$pro.name retrolambda config:")
                            println(lambdaConfig)
                            projectRetrolambdaConfig[pro.name] = lambdaConfig
                        } else {
                            println '[WARNING] JDK8 not found, skip retrolambda.'
                        }
                    }
                }

                // modify .class file
                def isLowerVersion = false
                if (!forceLowerVersion) {
                    project.rootProject.buildscript.configurations.classpath.resolvedConfiguration.firstLevelModuleDependencies.each {
                        if (it.moduleGroup == "com.android.tools.build" && it.moduleName == "gradle") {
                            if (!it.moduleVersion.startsWith("1.5")
                                    && !it.moduleVersion.startsWith("2")) {
                                isLowerVersion = true
                                return false
                            }
                        }
                    }
                } else {
                    isLowerVersion = true
                }

                def classesProcessTask
                def preDexTask
                def multiDexListTask
                boolean multiDexEnabled = variant.apkVariantData.variantConfiguration.isMultiDexEnabled()
                if (isLowerVersion) {
                    if (multiDexEnabled) {
                        classesProcessTask = project.tasks.findByName("packageAll${variant.name.capitalize()}ClassesForMultiDex")
                        multiDexListTask = project.tasks.findByName("create${variant.name.capitalize()}MainDexClassList")
                    } else {
                        classesProcessTask = project.tasks.findByName("dex${variant.name.capitalize()}")
                        preDexTask = project.tasks.findByName("preDex${variant.name.capitalize()}")
                    }
                } else {
                    String manifest_path = project.android.sourceSets.main.manifest.srcFile.path
                    if (getMinSdkVersion(variant.mergedFlavor, manifest_path) < 21 && multiDexEnabled) {
                        classesProcessTask = project.tasks.findByName("transformClassesWithJarMergingFor${variant.name.capitalize()}")
                        multiDexListTask = project.tasks.findByName("transformClassesWithMultidexlistFor${variant.name.capitalize()}")
                    } else {
                        classesProcessTask = project.tasks.findByName("transformClassesWithDexFor${variant.name.capitalize()}")
                    }
                }

                if (classesProcessTask == null) {
                    println "Skip ${project.name}'s hack process"
                    return
                }

                classesProcessTask.outputs.upToDateWhen { false }
                String backUpDirPath = FreelineUtils.getBuildBackupDir(project.buildDir.absolutePath)

                if (preDexTask) {
                    preDexTask.outputs.upToDateWhen { false }
                    def hackClassesBeforePreDex = "hackClassesBeforePreDex${variant.name.capitalize()}"
                    project.task(hackClassesBeforePreDex) << {
                        def jarDependencies = []
                        def modules = []
                        project.rootProject.allprojects.each { pro ->
                            modules.add("exploded-aar" + File.separator + pro.group + File.separator + pro.name + File.separator)
                        }

                        preDexTask.inputs.files.files.each { f ->
                            if (f.path.endsWith(".jar")) {
                                FreelineInjector.inject(excludeHackClasses, f as File, modules)
                                jarDependencies.add(f.path)
                            }
                        }
                        def json = new JsonBuilder(jarDependencies).toPrettyString()
                        project.logger.info(json)
                        FreelineUtils.saveJson(json, FreelineUtils.joinPath(FreelineUtils.getBuildCacheDir(project.buildDir.absolutePath), "jar_dependencies.json"), true);
                    }

                    def hackClassesBeforePreDexTask = project.tasks[hackClassesBeforePreDex]
                    hackClassesBeforePreDexTask.dependsOn preDexTask.taskDependencies.getDependencies(preDexTask)
                    preDexTask.dependsOn hackClassesBeforePreDexTask
                }

                def hackClassesBeforeDex = "hackClassesBeforeDex${variant.name.capitalize()}"
                def backupMap = [:]
                project.task(hackClassesBeforeDex) << {
                    def jarDependencies = []
                    def modules = []
                    project.rootProject.allprojects.each { pro ->
                        modules.add("exploded-aar" + File.separator + pro.group + File.separator + pro.name + File.separator)
                    }

                    classesProcessTask.inputs.files.files.each { f ->
                        if (f.isDirectory()) {
                            f.eachFileRecurse(FileType.FILES) { file ->
                                backUpClass(backupMap, file as File, backUpDirPath as String)
                                FreelineInjector.inject(excludeHackClasses, file as File, modules)
                                if (file.path.endsWith(".jar")) {
                                    jarDependencies.add(file.path)
                                }
                            }
                        } else {
                            backUpClass(backupMap, f as File, backUpDirPath as String)
                            FreelineInjector.inject(excludeHackClasses, f as File, modules)
                            if (f.path.endsWith(".jar")) {
                                jarDependencies.add(f.path)
                            }
                        }
                    }

                    if (preDexTask == null) {
                        jarDependencies.addAll(addtionalJars)  // add all additional jars to final jar dependencies
                        def json = new JsonBuilder(jarDependencies).toPrettyString()
                        project.logger.info(json)
                        FreelineUtils.saveJson(json, FreelineUtils.joinPath(FreelineUtils.getBuildCacheDir(project.buildDir.absolutePath), "jar_dependencies.json"), true);
                    }
                }

                if (classesProcessTask) {
                    def hackClassesBeforeDexTask = project.tasks[hackClassesBeforeDex]
                    hackClassesBeforeDexTask.dependsOn classesProcessTask.taskDependencies.getDependencies(classesProcessTask)
                    classesProcessTask.dependsOn hackClassesBeforeDexTask
                }

                if (multiDexEnabled && applicationProxy) {
                    def mainDexListFile = new File("${project.buildDir}/intermediates/multi-dex/${variant.dirName}/maindexlist.txt")
                    if (multiDexListTask) {
                        multiDexListTask.outputs.upToDateWhen { false }
                        multiDexListTask.doLast {
                            Constants.FREELINE_CLASSES.each { clazz ->
                                mainDexListFile << "\n${clazz}"
                            }
                        }
                    }
                }

                def assembleTask = project.tasks.findByName("assemble${variant.name.capitalize()}")
                if (assembleTask) {
                    assembleTask.doLast {
                        FreelineUtils.addNewAttribute(project, 'apt', projectAptConfig)
                        FreelineUtils.addNewAttribute(project, 'retrolambda', projectRetrolambdaConfig)
                        rollBackClasses(backupMap)
                    }
                }
            }
        }
    }

    private static void addFreelineGeneratedFiles(Project project, File targetDir, File dirEntry) {
        if (dirEntry == null) {
            dirEntry = new File(FreelineUtils.getBuildAssetsDir(project.buildDir.absolutePath))
        }
        if (dirEntry.exists() && dirEntry.isDirectory()) {
            dirEntry.eachFileRecurse(FileType.FILES) { f ->
                def target = new File(targetDir, f.name)
                if (target.exists()) {
                    target.delete()
                } else {
                    throw new GradleException("${target.absolutePath} file not found. \nMissing the `productFlavor` configuration?\nYou can try to add `productFlavor` to freeline DSL, for example: \n\n  freeline { \n      hack true \n      productFlavor 'your-flavor' \n  }\n\nThen re-run `python freeline.py` again.\n")
                }
                target << f.text
            }
        }
    }

    private static void findResourceDependencies(def variant, Project project, String buildCacheDir) {
        def mergeResourcesTask = project.tasks.findByName("merge${variant.name.capitalize()}Resources")
        def resourcesInterceptor = "resourcesInterceptorBeforeMerge${variant.name.capitalize()}Resources"
        if (mergeResourcesTask == null) {
            mergeResourcesTask = project.tasks.findByName("mergeReleaseResources")
        }
        if (mergeResourcesTask == null) {
            mergeResourcesTask = project.tasks.findByName("mergeDebugResources")
        }
        if (mergeResourcesTask == null) {
            println "${project.name} merge resources task not found."
            if (!project.hasProperty("android")) {
                return
            }

            def resourcesDependencies = ["local_resources": [], "library_resources": []]
            def searchDirs = [new File(project.buildDir, 'generated/res/resValues/release'),
                              new File(project.buildDir, 'generated/res/rs/release')]
            searchDirs.each { dir ->
                if (dir.exists()) {
                    resourcesDependencies.local_resources.add(dir.absolutePath)
                    println "add local resource: ${dir.absolutePath}"
                }
            }
            def json = new JsonBuilder(resourcesDependencies).toPrettyString()
            def cacheDir = new File(FreelineUtils.joinPath(FreelineUtils.joinPath(buildCacheDir, project.name)))
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            FreelineUtils.saveJson(json, FreelineUtils.joinPath(cacheDir.absolutePath, "resources_dependencies.json"), true)
            return
        }

        project.task(resourcesInterceptor) << {
            def dir = new File(FreelineUtils.joinPath(buildCacheDir, project.name))
            if (!dir.exists()) {
                dir.mkdirs()
            }

            def resourcesDependencies = ["local_resources": [], "library_resources": []]
            def mappers = []

            project.rootProject.allprojects.each { p ->
                if (p.hasProperty("android") && p.android.hasProperty("sourceSets")) {
                    def mapper = ["match" : "", "path" : []]
                    mapper.match = "exploded-aar${File.separator}${p.group}${File.separator}${p.name}${File.separator}"
                    p.android.sourceSets.main.res.srcDirs.asList().collect(mapper.path) { it.absolutePath }
                    mappers.add(mapper)
                }
            }

            def projectResDirs = []
            project.android.sourceSets.main.res.srcDirs.asList().collect(projectResDirs) { it.absolutePath }

            mergeResourcesTask.inputs.files.files.each { f ->
                if (f.exists() && f.isDirectory()) {
                    def path = f.absolutePath
                    println "find resource path: ${path}"
                    if (path.contains("exploded-aar")) {
                        def marker = false
                        mappers.each { mapper ->
                            if (path.contains(mapper.match as String)) {
                                mapper.path.collect(resourcesDependencies.local_resources) {it}
                                println "add local resource: ${path}"
                                marker = true
                                return false
                            }
                        }
                        if (!marker) {
                            resourcesDependencies.library_resources.add(path)
                            println "add library resource: ${path}"
                        }
                    } else {
                        if (!projectResDirs.contains(path)) {
                            resourcesDependencies.local_resources.add(path)
                            println "add local resource: ${path}"
                        }
                    }
                }
            }

            def json = new JsonBuilder(resourcesDependencies).toPrettyString()
            project.logger.info(json)
            FreelineUtils.saveJson(json, FreelineUtils.joinPath(dir.absolutePath, "resources_dependencies.json"), true);
        }

        def resourcesInterceptorTask = project.tasks[resourcesInterceptor]
        resourcesInterceptorTask.dependsOn mergeResourcesTask.taskDependencies.getDependencies(mergeResourcesTask)
        mergeResourcesTask.dependsOn resourcesInterceptorTask
    }

    private static void replaceApplication(String manifestPath) {
        def manifestFile = new File(manifestPath)
        def manifest = new XmlSlurper(false, false).parse(manifestFile)
        manifest.application."@android:name" = "com.antfortune.freeline.FreelineApplication"

        manifestFile.delete()
        manifestFile << XmlUtil.serialize(manifest)
    }

    private static int getMinSdkVersion(def mergedFlavor, String manifestPath) {
        if (mergedFlavor.minSdkVersion != null) {
            return mergedFlavor.minSdkVersion.apiLevel
        } else {
            return FreelineParser.getMinSdkVersion(manifestPath)
        }
    }

    private static void backUpClass(def backupMap, File file, String backUpDirPath) {
        String path = file.absolutePath
        if (!FreelineUtils.isEmpty(path) && path.endsWith(".class") && isNeedBackUp(path)) {
            File target = new File(backUpDirPath, String.valueOf(System.currentTimeMillis()))
            FreelineUtils.copyFile(file, target)
            backupMap[file.absolutePath] = target.absolutePath
            println "back up ${file.absolutePath} to ${target.absolutePath}"
        }
    }

    private static boolean isNeedBackUp(String path) {
        def pattern
        if (FreelineUtils.isWindows()) {
            pattern = ~".*\\\\R\\\$?\\w*.class"
        } else {
            pattern = ~".*/R\\\$?\\w*.class"
        }
        return pattern.matcher(path).matches()
    }

    private static void rollBackClasses(def backupMap) {
        backupMap.each { targetPath, sourcePath ->
            File sourceFile = new File(sourcePath as String)
            if (sourceFile.exists()) {
                FreelineUtils.copyFile(sourceFile, new File(targetPath as String))
                println "roll back ${targetPath}"
            }
        }
    }

    private static boolean isProguardEnable(Project project, def variant) {
        def proguardTask = project.tasks.findByName("transformClassesAndResourcesWithProguardFor${variant.name.capitalize()}")
        if (proguardTask == null) {
            proguardTask = project.tasks.findByName("proguard${variant.name.capitalize()}")
        }
        return proguardTask != null
    }

    private static boolean isMultiDexEnabled(Project project, def variant) {
        if (variant.buildType.multiDexEnabled != null) {
            return variant.buildType.multiDexEnabled
        }
        if (variant.mergedFlavor.multiDexEnabled != null) {
            return variant.mergedFlavor.multiDexEnabled
        }
        return project.android.defaultConfig.multiDexEnabled
    }

    private static String isAptEnabled(Project project) {
        boolean isAptEnabled = false
        project.rootProject.buildscript.configurations.classpath.resolvedConfiguration.firstLevelModuleDependencies.each {
            if (it.moduleGroup == "com.neenbedankt.gradle.plugins" && it.moduleName == "android-apt") {
                isAptEnabled = true
                return false
            }
        }
        return isAptEnabled
    }

    private static VersionNumber retrolambdaVersion(Configuration retrolambdaConfig) {
        retrolambdaConfig.resolve()
        Dependency retrolambdaDep = retrolambdaConfig.dependencies.iterator().next()
        if (!retrolambdaDep.version) {
            // Don't know version
            return null
        }
        return VersionNumber.parse(retrolambdaDep.version)

    }

    private static boolean requireVersion(VersionNumber retrolambdaVersion, String version, boolean fallback = false) {
        if (retrolambdaVersion == null) {
            // Don't know version, assume fallback
            return fallback
        }
        def targetVersionNumber = VersionNumber.parse(version)
        return retrolambdaVersion >= targetVersionNumber
    }

    private static String getJdk8() {
        if ((System.properties.'java.version' as String).startsWith('1.8')) {
            return FreelineInitializer.getJavaHome()
        } else {
            return System.getenv("JAVA8_HOME")
        }
    }

}



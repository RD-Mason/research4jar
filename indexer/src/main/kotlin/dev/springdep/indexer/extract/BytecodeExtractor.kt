package dev.springdep.indexer.extract

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.util.TreeMap
import java.util.zip.ZipFile

data class BytecodeExtraction(
    val classes: List<ExtractedClass>,
    val methods: List<ExtractedMethod>,
    val annotations: List<ExtractedAnnotation>,
    val beanDefinitions: List<ExtractedBeanDefinition>,
    val conditions: List<ExtractedCondition>,
    val stringConstants: List<ExtractedStringConstant>,
    val warnings: List<String>,
)

class BytecodeExtractor(
    objectMapper: ObjectMapper,
) {
    private val json = objectMapper.copy()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)

    fun extract(zip: ZipFile): BytecodeExtraction {
        val sink = ExtractionSink()
        val warnings = mutableListOf<String>()
        zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".class") }
            // Base-only: a multi-release jar's META-INF/versions/<N>/ classes would
            // otherwise re-extract the same FQN and produce duplicate rows.
            .filter { !it.name.startsWith("META-INF/versions/") }
            .sortedBy { it.name }
            .forEach { entry ->
                try {
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    ClassReader(bytes).accept(
                        ExtractingClassVisitor(sink, json),
                        ClassReader.SKIP_FRAMES,
                    )
                } catch (exception: Exception) {
                    warnings +=
                        "skipped class ${entry.name}: ${exception.message ?: exception.javaClass.name}"
                }
            }
        return sink.result(warnings)
    }
}

private class ExtractionSink {
    val classes = mutableListOf<ExtractedClass>()
    val methods = mutableListOf<ExtractedMethod>()
    val annotations = mutableListOf<ExtractedAnnotation>()
    val beanDefinitions = mutableListOf<ExtractedBeanDefinition>()
    val conditions = mutableListOf<ExtractedCondition>()
    val stringConstants = mutableListOf<ExtractedStringConstant>()

    fun result(warnings: List<String>) = BytecodeExtraction(
        classes = classes.sortedBy { it.fqn },
        methods = methods.sortedWith(
            compareBy<ExtractedMethod>({ it.classFqn }, { it.name }, { it.descriptor }),
        ),
        annotations = annotations.sortedWith(
            compareBy<ExtractedAnnotation>(
                { it.targetKind },
                { it.classFqn },
                { it.methodName ?: "" },
                { it.methodDescriptor ?: "" },
                { it.annotationFqn },
                { it.attributes },
            ),
        ),
        beanDefinitions = beanDefinitions.sortedWith(
            compareBy<ExtractedBeanDefinition>(
                { it.configFqn },
                { it.methodName },
                { it.methodDescriptor },
            ),
        ),
        conditions = conditions.sortedWith(
            compareBy<ExtractedCondition>(
                { it.targetKind },
                { it.classFqn },
                { it.methodName ?: "" },
                { it.methodDescriptor ?: "" },
                { it.type },
                { it.refValue },
            ),
        ),
        stringConstants = stringConstants.sortedWith(
            compareBy<ExtractedStringConstant>(
                { it.classFqn },
                { it.value },
                { it.methodName ?: "" },
                { it.methodDescriptor ?: "" },
            ),
        ),
        warnings = warnings.sorted(),
    )
}

private class ExtractingClassVisitor(
    private val sink: ExtractionSink,
    private val json: ObjectMapper,
) : ClassVisitor(Opcodes.ASM9) {
    private lateinit var classFqn: String
    private var kind: String = "class"
    private var superFqn: String? = null
    private var modifiers: Int = 0
    private var sourceFile: String? = null
    private var interfaces: List<String> = emptyList()

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
    ) {
        classFqn = internalNameToFqn(name)
        modifiers = access
        superFqn = superName?.let(::internalNameToFqn)?.takeUnless { it == "java.lang.Object" }
        this.interfaces = interfaces.orEmpty().map(::internalNameToFqn).sorted()
        kind = when {
            access and Opcodes.ACC_ANNOTATION != 0 -> "annotation"
            access and Opcodes.ACC_INTERFACE != 0 -> "interface"
            access and Opcodes.ACC_ENUM != 0 -> "enum"
            access and Opcodes.ACC_RECORD != 0 || superFqn == "java.lang.Record" -> "record"
            else -> "class"
        }
    }

    override fun visitSource(source: String?, debug: String?) {
        sourceFile = source
    }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
        val annotationFqn = Type.getType(descriptor).className
        return CollectingAnnotationVisitor(json, onComplete = { attributes, _ ->
            sink.annotations += ExtractedAnnotation(
                targetKind = "class",
                classFqn = classFqn,
                annotationFqn = annotationFqn,
                attributes = attributes,
            )
            conditionType(annotationFqn)?.let { type ->
                sink.conditions += ExtractedCondition(
                    targetKind = "class",
                    classFqn = classFqn,
                    type = type,
                    refValue = attributes,
                )
            }
        })
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor? {
        if (access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) != 0) return null
        val returnType = Type.getReturnType(descriptor)
        val returnFqn = returnType.className.takeUnless { returnType.sort == Type.VOID }
        sink.methods += ExtractedMethod(classFqn, name, descriptor, returnFqn, access)
        return ExtractingMethodVisitor(
            sink = sink,
            json = json,
            classFqn = classFqn,
            methodName = name,
            methodDescriptor = descriptor,
            returnFqn = returnFqn,
        )
    }

    override fun visitField(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        value: Any?,
    ): FieldVisitor? {
        if (value is String) {
            sink.stringConstants += ExtractedStringConstant(classFqn = classFqn, value = value)
        }
        return null
    }

    override fun visitEnd() {
        sink.classes += ExtractedClass(
            fqn = classFqn,
            kind = kind,
            superFqn = superFqn,
            modifiers = modifiers,
            isAbstract = modifiers and Opcodes.ACC_ABSTRACT != 0,
            sourceFile = sourceFile,
            interfaces = interfaces,
        )
    }
}

private class ExtractingMethodVisitor(
    private val sink: ExtractionSink,
    private val json: ObjectMapper,
    private val classFqn: String,
    private val methodName: String,
    private val methodDescriptor: String,
    private val returnFqn: String?,
) : MethodVisitor(Opcodes.ASM9) {
    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
        val annotationFqn = Type.getType(descriptor).className
        return CollectingAnnotationVisitor(json, onComplete = { attributes, values ->
            sink.annotations += ExtractedAnnotation(
                targetKind = "method",
                classFqn = classFqn,
                methodName = methodName,
                methodDescriptor = methodDescriptor,
                annotationFqn = annotationFqn,
                attributes = attributes,
            )
            conditionType(annotationFqn)?.let { type ->
                sink.conditions += ExtractedCondition(
                    targetKind = "bean_method",
                    classFqn = classFqn,
                    methodName = methodName,
                    methodDescriptor = methodDescriptor,
                    type = type,
                    refValue = attributes,
                )
            }
            if (annotationFqn == BEAN_ANNOTATION) {
                sink.beanDefinitions += ExtractedBeanDefinition(
                    configFqn = classFqn,
                    methodName = methodName,
                    methodDescriptor = methodDescriptor,
                    beanTypeFqn = returnFqn,
                    beanName = firstBeanName(values) ?: methodName,
                )
            }
        })
    }

    override fun visitLdcInsn(value: Any?) {
        if (value is String) {
            sink.stringConstants += ExtractedStringConstant(
                classFqn = classFqn,
                methodName = methodName,
                methodDescriptor = methodDescriptor,
                value = value,
            )
        }
    }
}

private class CollectingAnnotationVisitor(
    private val json: ObjectMapper,
    private val onComplete: (String, Map<String, Any?>) -> Unit,
    private val values: MutableMap<String, Any?> = TreeMap(),
) : AnnotationVisitor(Opcodes.ASM9) {
    override fun visit(name: String?, value: Any?) {
        if (name != null) values[name] = normalizeAnnotationValue(value)
    }

    override fun visitEnum(name: String?, descriptor: String?, value: String?) {
        if (name != null) values[name] = value
    }

    override fun visitAnnotation(name: String?, descriptor: String?): AnnotationVisitor {
        val nested = TreeMap<String, Any?>()
        if (name != null) values[name] = nested
        return CollectingAnnotationVisitor(json, { _, _ -> }, nested)
    }

    override fun visitArray(name: String?): AnnotationVisitor {
        val items = mutableListOf<Any?>()
        if (name != null) values[name] = items
        return CollectingArrayVisitor(json, items)
    }

    override fun visitEnd() {
        onComplete(json.writeValueAsString(values), values)
    }
}

private class CollectingArrayVisitor(
    private val json: ObjectMapper,
    private val items: MutableList<Any?>,
) : AnnotationVisitor(Opcodes.ASM9) {
    override fun visit(name: String?, value: Any?) {
        items.add(normalizeAnnotationValue(value))
    }

    override fun visitEnum(name: String?, descriptor: String?, value: String?) {
        items.add(value)
    }

    override fun visitAnnotation(name: String?, descriptor: String?): AnnotationVisitor {
        val nested = TreeMap<String, Any?>()
        items.add(nested)
        return CollectingAnnotationVisitor(json, { _, _ -> }, nested)
    }

    override fun visitArray(name: String?): AnnotationVisitor {
        val nested = mutableListOf<Any?>()
        items.add(nested)
        return CollectingArrayVisitor(json, nested)
    }
}

private fun normalizeAnnotationValue(value: Any?): Any? = when (value) {
    is Type -> value.className
    is Char -> value.toString()
    else -> value
}

private fun firstBeanName(values: Map<String, Any?>): String? =
    sequenceOf(values["name"], values["value"])
        .mapNotNull { value ->
            when (value) {
                is String -> value.takeIf(String::isNotBlank)
                is List<*> -> value.firstOrNull { it is String && it.isNotBlank() } as String?
                else -> null
            }
        }
        .firstOrNull()

private fun conditionType(annotationFqn: String): String? {
    if (annotationFqn == CONDITIONAL_ANNOTATION) return "Conditional"
    if (!annotationFqn.startsWith(CONDITIONAL_ON_PREFIX)) return null
    return annotationFqn.substringAfterLast('.').removePrefix("Conditional")
}

private fun internalNameToFqn(name: String): String = name.replace('/', '.')

private const val BEAN_ANNOTATION = "org.springframework.context.annotation.Bean"
private const val CONDITIONAL_ANNOTATION = "org.springframework.context.annotation.Conditional"
private const val CONDITIONAL_ON_PREFIX =
    "org.springframework.boot.autoconfigure.condition.ConditionalOn"

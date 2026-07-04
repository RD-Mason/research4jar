package dev.research4jar.indexer.extract

data class ConfigProperty(
    val prefix: String?,
    val name: String,
    val typeFqn: String?,
    val defaultValue: String?,
    val description: String?,
    val sourceFqn: String?,
)

data class SpiRegistration(
    val mechanism: String,
    val key: String?,
    val implFqn: String,
)

data class ExtractedClass(
    val fqn: String,
    val kind: String,
    val superFqn: String?,
    val modifiers: Int,
    val isAbstract: Boolean,
    val sourceFile: String?,
    val interfaces: List<String>,
)

data class ExtractedMethod(
    val classFqn: String,
    val name: String,
    val descriptor: String,
    val returnFqn: String?,
    val modifiers: Int,
)

data class ExtractedAnnotation(
    val targetKind: String,
    val classFqn: String,
    val methodName: String? = null,
    val methodDescriptor: String? = null,
    val annotationFqn: String,
    val attributes: String,
)

data class ExtractedBeanDefinition(
    val configFqn: String,
    val methodName: String,
    val methodDescriptor: String,
    val beanTypeFqn: String?,
    val beanName: String,
)

data class ExtractedCondition(
    val targetKind: String,
    val classFqn: String,
    val methodName: String? = null,
    val methodDescriptor: String? = null,
    val type: String,
    val refValue: String,
)

data class ExtractedStringConstant(
    val classFqn: String,
    val methodName: String? = null,
    val methodDescriptor: String? = null,
    val value: String,
)

data class ExtractedJar(
    val coordinate: String?,
    val spiRegistrations: List<SpiRegistration> = emptyList(),
    val configProperties: List<ConfigProperty> = emptyList(),
    val classes: List<ExtractedClass> = emptyList(),
    val methods: List<ExtractedMethod> = emptyList(),
    val annotations: List<ExtractedAnnotation> = emptyList(),
    val beanDefinitions: List<ExtractedBeanDefinition> = emptyList(),
    val conditions: List<ExtractedCondition> = emptyList(),
    val stringConstants: List<ExtractedStringConstant> = emptyList(),
    val warnings: List<String> = emptyList(),
)

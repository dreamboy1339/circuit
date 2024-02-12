// Copyright (C) 2024 Slack Technologies, LLC
// SPDX-License-Identifier: Apache-2.0
package com.slack.circuit.codegen.kotlininject

import com.google.auto.service.AutoService
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.slack.circuit.codegen.CircuitNames
import com.slack.circuit.codegen.CircuitSymbols
import com.slack.circuit.codegen.CodegenMode
import com.slack.circuit.codegen.getAnnotationsByType
import com.slack.circuit.codegen.isInstanceOf
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.TypeParameterResolver
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import kotlin.reflect.KClass

/** TODO */
public class MergeCircuitComponentProcessor(
  private val enabled: Boolean,
  private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
) : SymbolProcessor {
  @AutoService(SymbolProcessorProvider::class)
  public class Provider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
      val enabled =
        environment.options[CircuitNames.CIRCUIT_CODEGEN_MODE]?.equals(
          CodegenMode.KOTLIN_INJECT.name,
          ignoreCase = true,
        ) ?: false
      environment.logger.warn("Circuit KotlinInject codegen enabled: $enabled")
      return MergeCircuitComponentProcessor(enabled, environment.codeGenerator, environment.logger)
    }
  }

  override fun process(resolver: Resolver): List<KSAnnotated> {
    logger.warn("Processing merge circuit components")
    if (!enabled) return emptyList()
    // Get all merge circuit component annotations
    val mergeCircuitComponents =
      resolver
        .getSymbolsWithAnnotation(CircuitNames.MERGE_CIRCUIT_COMPONENT_ANNOTATION.canonicalName)
        .filterIsInstance<KSClassDeclaration>()
        .onEach { logger.warn("Found merge circuit component: $it") }
        .map {
          val mergeAnnotation =
            it.getAnnotationsByType(CircuitNames.MERGE_CIRCUIT_COMPONENT_ANNOTATION).first()
          val scopeType = mergeAnnotation.arguments.first().value as KSType
          val scope = (scopeType.declaration as KSClassDeclaration).toClassName()
          // Read the ParentComponent off the generic type (which is stored on
          // annotationType.element)
          val parentComponent =
            mergeAnnotation.annotationType.element!!
              .typeArguments[0]
              .toTypeName(TypeParameterResolver.EMPTY)
          MergeComponentData(scope, parentComponent, it)
        }
        .toList()

    if (mergeCircuitComponents.isEmpty()) return emptyList()

    // Get all kotlin inject hints, associate by scope
    val hints: MutableMap<ClassName, MutableSet<KSClassDeclaration>> = mutableMapOf()
    resolver
      .getSymbolsWithAnnotation(CircuitNames.KOTLIN_INJECT_HINT_ANNOTATION.canonicalName)
      .filterIsInstance<KSClassDeclaration>()
      .onEach { logger.warn("Found kotlin inject hint: $it") }
      .forEach { clazz ->
        clazz
          .getAnnotationsByType(CircuitNames.KOTLIN_INJECT_HINT_ANNOTATION)
          .map {
            val type = it.arguments.first().value as KSType
            val decl = type.declaration as KSClassDeclaration
            decl.toClassName()
          }
          .forEach { scope -> hints.getOrPut(scope, ::mutableSetOf).add(clazz) }
      }

    val symbols = CircuitSymbols.create(resolver) ?: return emptyList()
    val deferred = mutableListOf<KSAnnotated>()
    for (mergeComponent in mergeCircuitComponents) {
      val scope = mergeComponent.scope
      val contributedFactories = hints[scope] ?: emptySet()
      val annotated = mergeComponent.annotated
      if (contributedFactories.isEmpty()) {
        logger.warn("No contributed factories found for scope $scope", annotated)
        deferred += annotated
        continue
      }

      val parentComponent = mergeComponent.parentComponent
      logger.warn("Generating component for scope $scope with parent component $parentComponent")
      generate(symbols, annotated, contributedFactories, parentComponent)
    }
    return deferred
  }

  private fun generate(
    symbols: CircuitSymbols,
    annotated: KSClassDeclaration,
    contributedFactories: Set<KSClassDeclaration>,
    parentComponent: TypeName,
  ) {
    val annotatedClassName = annotated.toClassName()

    // Generate the component implementation
    /*
    @Component
    abstract class AppScopeCircuitComponentImpl(
      @Component
      public val parentComponent: ParentComponent,
    ): AppScopeCircuitComponent {

      protected val OtherScreenPresenterFactory.bind: Presenter.Factory
        @Provides
        @IntoSet
        get() = this

      protected val MyScreenFactory.bind: Ui.Factory
        @Provides
        @IntoSet
        get() = this
    }
    */
    val componentImplName = "${annotatedClassName.simpleName}Impl"
    val componentImpl =
      TypeSpec.classBuilder(componentImplName)
        .addAnnotation(CircuitNames.KotlinInject.COMPONENT)
        .addModifiers(KModifier.ABSTRACT)
        .addSuperinterface(annotatedClassName)
        .primaryConstructor(
          FunSpec.constructorBuilder()
            .addParameter(
              ParameterSpec.builder("parentComponent", parentComponent)
                .addAnnotation(CircuitNames.KotlinInject.COMPONENT)
                .build()
            )
            .build()
        )
        .addProperty(
          PropertySpec.builder("parentComponent", parentComponent)
            .initializer("parentComponent")
            .build()
        )
        .addOriginatingKSFile(annotated.containingFile!!)
        .apply {
          contributedFactories.forEach { contributedFactory ->
            val factoryType = contributedFactory.toClassName()
            val factoryKsType = contributedFactory.asType(emptyList())
            val factorySuperType =
              when {
                factoryKsType.isInstanceOf(symbols.presenterFactory) -> {
                  CircuitNames.CIRCUIT_PRESENTER_FACTORY
                }
                factoryKsType.isInstanceOf(symbols.uiFactory) -> {
                  CircuitNames.CIRCUIT_UI_FACTORY
                }
                else -> {
                  logger.error(
                    "Contributed factory $factoryType is not a Presenter.Factory or Ui.Factory",
                    contributedFactory,
                  )
                  return@forEach
                }
              }
            val factoryProperty =
              PropertySpec.builder("bind", factorySuperType)
                .addModifiers(KModifier.PROTECTED)
                .receiver(factoryType)
                .getter(
                  FunSpec.getterBuilder()
                    .addAnnotation(CircuitNames.KotlinInject.PROVIDES)
                    .addAnnotation(CircuitNames.KotlinInject.INTO_SET)
                    .addStatement("return this")
                    .build()
                )
                .addOriginatingKSFile(contributedFactory.containingFile!!)
                .build()
            addProperty(factoryProperty)
          }
        }
        .build()

    /*
    internal fun KClass<AppScopeCircuitComponent>.create(parentComponent: ParentComponent): AppScopeCircuitComponent =
        AppScopeCircuitComponentImpl::class.create(parentComponent)
     */
    val createFunction =
      FunSpec.builder("create")
        .receiver(KClass::class.asClassName().parameterizedBy(annotatedClassName))
        .addParameter("parentComponent", parentComponent)
        .returns(annotatedClassName)
        .addStatement(
          "return %T::class.create(parentComponent)",
          ClassName(annotatedClassName.packageName, componentImplName),
        )
        .addOriginatingKSFile(annotated.containingFile!!)
        .build()

    FileSpec.builder(annotatedClassName.packageName, componentImplName)
      .addType(componentImpl)
      .addFunction(createFunction)
      .build()
      .writeTo(codeGenerator, aggregating = true)
  }
}

internal data class MergeComponentData(
  val scope: ClassName,
  val parentComponent: TypeName,
  val annotated: KSClassDeclaration,
)
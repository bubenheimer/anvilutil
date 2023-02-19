/*
 * Copyright (c) 2015-2023 Uli Bubenheimer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalAnvilApi::class)

package org.bubenheimer.anvil.generator

import com.google.auto.service.AutoService
import com.squareup.anvil.annotations.ContributesTo
import com.squareup.anvil.annotations.ExperimentalAnvilApi
import com.squareup.anvil.compiler.api.AnvilContext
import com.squareup.anvil.compiler.api.CodeGenerator
import com.squareup.anvil.compiler.api.GeneratedFile
import com.squareup.anvil.compiler.api.createGeneratedFile
import com.squareup.anvil.compiler.internal.buildFile
import com.squareup.anvil.compiler.internal.fqName
import com.squareup.anvil.compiler.internal.reference.*
import com.squareup.anvil.compiler.internal.safePackageString
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.AnnotationSpec.UseSiteTarget.GET
import dagger.Binds
import dagger.Module
import dagger.Provides
import org.bubenheimer.anvil.annotations.AndBinds
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

@AutoService(CodeGenerator::class)
internal class AndBindsCodeGenerator : CodeGenerator {
    override fun isApplicable(context: AnvilContext) = !context.generateFactoriesOnly

    override fun generateCode(
            codeGenDir: File,
            module: ModuleDescriptor,
            projectFiles: Collection<KtFile>
    ): Collection<GeneratedFile> = projectFiles
            .classAndInnerClassReferences(module)
            .filter { it.isAnnotatedWith(daggerModuleFqName) }
            .mapNotNull { clazz ->
                val allModuleClasses: Sequence<ClassReference> =
                        (clazz.companionObjects() + clazz).asSequence()

                val functionBindings: Sequence<Binding> = allModuleClasses
                        .flatMap { it.functions }
                        .filterForAndBindsAnnotations()
                        .flatMap {
                            it.annotations
                                    .checkRequiredAnnotations(it)
                                    .extractBindings(it.name, it.returnType().asClassReference())
                        }

                val propertyBindings: Sequence<Binding> = allModuleClasses
                        .flatMap { it.properties }
                        .filterForAndBindsAnnotations()
                        .flatMap {
                            it.annotations
                                    .checkRequiredAnnotations(it)
                                    .extractBindings(it.name, it.type().asClassReference())
                        }

                val bindings: List<Binding> = (functionBindings + propertyBindings).toList()

                if (bindings.isEmpty()) return@mapNotNull null

                val scopes: Sequence<ClassReference> = clazz.annotations
                        .asSequence()
                        .filter { it.fqName == contributesToFqName }
                        .map { it.scope() }

                createGeneratedFile(codeGenDir, clazz, bindings, scopes)
            }
            .toList()

    private fun createGeneratedFile(
            codeGenDir: File,
            clazz: ClassReference,
            bindings: Collection<Binding>,
            scopes: Sequence<ClassReference>
    ): GeneratedFile {
        val packageName: String = clazz.packageFqName.safePackageString()

        val className: String =
                clazz.generateClassName(suffix = "AndBindsCodeGen").relativeClassName.asString()

        val content: String = FileSpec.buildFile(packageName, className) {
            addType(
                    TypeSpec.interfaceBuilder(ClassName(packageName, className))
                            .addAnnotation(Module::class)
                            .apply {
                                scopes.forEach {
                                    addAnnotation(
                                            AnnotationSpec
                                                    .builder(ContributesTo::class)
                                                    .addMember("${it.fqName}::class")
                                                    .build()
                                    )
                                }

                                bindings.forEach { binding ->
                                    addProperty(
                                            PropertySpec.builder(
                                                    binding.memberName,
                                                    binding.clazz.asTypeName(),
                                                    KModifier.ABSTRACT
                                            )
                                                    .addAnnotation(
                                                            AnnotationSpec.builder(Binds::class)
                                                                    .useSiteTarget(GET)
                                                                    .build()
                                                    )
                                                    .receiver(binding.memberType.asTypeName())
                                                    .build()
                                    )
                                }
                            }
                            .build()
            )
        }

        return createGeneratedFile(
                codeGenDir = codeGenDir,
                packageName = packageName,
                fileName = className,
                content = content
        )
    }
}

private data class Binding(
        val memberName: String,
        val memberType: ClassReference,
        val clazz: ClassReference,
        val qualifiers: Sequence<AnnotationReference>
)

private fun <T : AnnotatedReference> Sequence<T>.filterForAndBindsAnnotations(): Sequence<T> =
        filter { it.isAnnotatedWith(annotationFqName) }

private fun List<AnnotationReference>.checkRequiredAnnotations(
        functionReference: FunctionReference
): List<AnnotationReference> = if (checkRequiredAnnotations()) this
else throw AnvilCompilationExceptionFunctionReference(functionReference, requiredAnnotationsError)

private fun List<AnnotationReference>.checkRequiredAnnotations(
        propertyReference: PropertyReference
): List<AnnotationReference> = if (checkRequiredAnnotations()) this
else throw AnvilCompilationExceptionPropertyReference(propertyReference, requiredAnnotationsError)

private fun List<AnnotationReference>.checkRequiredAnnotations(): Boolean =
        any { it.fqName == daggerProvidesFqName }

private const val requiredAnnotationsError: String =
        "AndBinds annotation may only appear on elements annotated with @Provides"

private fun List<AnnotationReference>.extractBindings(
        memberName: String,
        memberType: ClassReference
): Sequence<Binding> {
    val memberSuperTypes: Sequence<ClassReference> = memberType.allSuperTypeClassReferences()

    val qualifiers: Sequence<AnnotationReference> = asSequence().filter { it.isQualifier() }

    return asSequence()
            .filter { it.fqName == annotationFqName }
            .map {
                val clazz: ClassReference = it.argumentAt("boundType", 0)!!.value()

                memberSuperTypes.firstOrNull { it.fqName == clazz.fqName }
                        ?: throw AnvilCompilationExceptionAnnotationReference(
                                it,
                                "boundType is not a super type of bound entity"
                        )

                Binding(
                        memberName = memberName,
                        memberType = memberType,
                        clazz = clazz,
                        qualifiers = qualifiers
                )
            }
}

private val daggerModuleFqName = Module::class.fqName

private val annotationFqName: FqName = AndBinds::class.fqName

private val daggerProvidesFqName = Provides::class.fqName

private val contributesToFqName: FqName = ContributesTo::class.fqName

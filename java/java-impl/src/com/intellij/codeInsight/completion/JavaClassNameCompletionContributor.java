/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInsight.completion;

import com.intellij.codeInsight.ExpectedTypeInfo;
import com.intellij.codeInsight.ExpectedTypesProvider;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.lang.LangBundle;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.patterns.PsiJavaElementPattern;
import com.intellij.psi.*;
import com.intellij.psi.filters.ClassFilter;
import com.intellij.psi.filters.ElementFilter;
import com.intellij.psi.filters.TrueFilter;
import com.intellij.psi.filters.element.ExcludeDeclaredFilter;
import com.intellij.psi.filters.types.AssignableFromFilter;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.Consumer;
import com.intellij.util.SmartList;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static com.intellij.patterns.PsiJavaPatterns.psiElement;

/**
 * @author peter
 */
public class JavaClassNameCompletionContributor extends CompletionContributor {
  public static final PsiJavaElementPattern.Capture<PsiElement> AFTER_NEW = psiElement().afterLeaf(PsiKeyword.NEW);
  private static final PsiJavaElementPattern.Capture<PsiElement> IN_TYPE_PARAMETER =
      psiElement().afterLeaf(PsiKeyword.EXTENDS, PsiKeyword.SUPER, "&").withParent(
          psiElement(PsiReferenceList.class).withParent(PsiTypeParameter.class));

  @Override
  public void fillCompletionVariants(CompletionParameters parameters, final CompletionResultSet _result) {
    if (parameters.isExtendedCompletion()) {
      if (shouldShowSecondSmartCompletionHint(parameters) &&
          CompletionUtil.shouldShowFeature(parameters, CodeCompletionFeatures.SECOND_CLASS_NAME_COMPLETION)) {
        CompletionService.getCompletionService().setAdvertisementText(CompletionBundle.message("completion.class.name.hint.2", getActionShortcut(IdeActions.ACTION_CODE_COMPLETION)));
      }

      CompletionResultSet result = _result.withPrefixMatcher(CompletionUtil.findReferenceOrAlphanumericPrefix(parameters));
      addAllClasses(parameters, parameters.getInvocationCount() <= 1,
                    JavaCompletionSorting.addJavaSorting(parameters, result).getPrefixMatcher(), new Consumer<LookupElement>() {
        @Override
        public void consume(LookupElement element) {
          _result.addElement(element);
        }
      });
    }
  }

  public static void addAllClasses(CompletionParameters parameters,
                                   final boolean filterByScope,
                                   @NotNull final PrefixMatcher matcher,
                                   @NotNull final Consumer<LookupElement> consumer) {
    final PsiElement insertedElement = parameters.getPosition();

    final ElementFilter filter;
    if (JavaSmartCompletionContributor.AFTER_THROW_NEW.accepts(insertedElement) ||
        JavaCompletionContributor.INSIDE_METHOD_THROWS_CLAUSE.accepts(insertedElement) ||
        JavaCompletionContributor.IN_CATCH_TYPE.accepts(insertedElement) ||
        JavaCompletionContributor.IN_MULTI_CATCH_TYPE.accepts(insertedElement)) {
      filter = new AssignableFromFilter(CommonClassNames.JAVA_LANG_THROWABLE);
    }
    else if (JavaCompletionContributor.IN_RESOURCE_TYPE.accepts(insertedElement)) {
      filter = new AssignableFromFilter(CommonClassNames.JAVA_LANG_AUTO_CLOSEABLE);
    }
    else if (IN_TYPE_PARAMETER.accepts(insertedElement)) {
      filter = new ExcludeDeclaredFilter(new ClassFilter(PsiTypeParameter.class));
    }
    else {
      filter = TrueFilter.INSTANCE;
    }

    final boolean inJavaContext = parameters.getPosition() instanceof PsiIdentifier;
    final boolean afterNew = AFTER_NEW.accepts(insertedElement);
    if (afterNew) {
      final PsiExpression expr = PsiTreeUtil.getContextOfType(insertedElement, PsiExpression.class, true);
      for (final ExpectedTypeInfo info : ExpectedTypesProvider.getExpectedTypes(expr, true)) {
        final PsiType type = info.getType();
        final PsiClass psiClass = PsiUtil.resolveClassInType(type);
        if (psiClass != null) {
          consumer.consume(createClassLookupItem(psiClass, inJavaContext));
        }
        final PsiType defaultType = info.getDefaultType();
        if (!defaultType.equals(type)) {
          final PsiClass defClass = PsiUtil.resolveClassInType(defaultType);
          if (defClass != null) {
            consumer.consume(createClassLookupItem(defClass, true));
          }
        }
      }
    }

    final boolean lookingForAnnotations = psiElement().afterLeaf("@").accepts(insertedElement);
    final boolean pkgContext = JavaCompletionUtil.inSomePackage(insertedElement);
    AllClassesGetter.processJavaClasses(parameters, matcher, filterByScope, new Consumer<PsiClass>() {
        @Override
        public void consume(PsiClass psiClass) {
          if (lookingForAnnotations && !psiClass.isAnnotationType()) return;

          if (filter.isAcceptable(psiClass, insertedElement)) {
            if (!inJavaContext) {
              consumer.consume(AllClassesGetter.createLookupItem(psiClass, AllClassesGetter.TRY_SHORTENING));
            } else {
              for (JavaPsiClassReferenceElement element : createClassLookupItems(psiClass, afterNew,
                                                                                 JavaClassNameInsertHandler.JAVA_CLASS_INSERT_HANDLER, new Condition<PsiClass>() {
                @Override
                public boolean value(PsiClass psiClass) {
                  return filter.isAcceptable(psiClass, insertedElement) &&
                         AllClassesGetter.isAcceptableInContext(insertedElement, psiClass, filterByScope, pkgContext);
                }
              })) {
                consumer.consume(element);
              }
            }
          }
        }
      });
  }

  public static JavaPsiClassReferenceElement createClassLookupItem(final PsiClass psiClass, final boolean inJavaContext) {
    return AllClassesGetter.createLookupItem(psiClass, inJavaContext ? JavaClassNameInsertHandler.JAVA_CLASS_INSERT_HANDLER
                                                                     : AllClassesGetter.TRY_SHORTENING);
  }

  public static List<JavaPsiClassReferenceElement> createClassLookupItems(final PsiClass psiClass,
                                                                          boolean withInners,
                                                                          InsertHandler<JavaPsiClassReferenceElement> insertHandler,
                                                                          Condition<PsiClass> condition) {
    List<JavaPsiClassReferenceElement> result = new SmartList<JavaPsiClassReferenceElement>();
    if (condition.value(psiClass)) {
      result.add(AllClassesGetter.createLookupItem(psiClass, insertHandler));
    }
    String name = psiClass.getName();
    if (withInners && name != null) {
      for (PsiClass inner : psiClass.getInnerClasses()) {
        if (inner.hasModifierProperty(PsiModifier.STATIC)) {
          for (JavaPsiClassReferenceElement lookupInner : createClassLookupItems(inner, withInners, insertHandler, condition)) {
            String forced = lookupInner.getForcedPresentableName();
            lookupInner.setForcedPresentableName(name + "." + (forced != null ? forced : inner.getName()));
            result.add(lookupInner);
          }
        }
      }
    }
    return result;
  }



  @Override
  public String handleEmptyLookup(@NotNull final CompletionParameters parameters, final Editor editor) {
    if (!(parameters.getOriginalFile() instanceof PsiJavaFile)) return null;

    if (shouldShowSecondSmartCompletionHint(parameters)) {
      return LangBundle.message("completion.no.suggestions") +
             "; " +
             StringUtil.decapitalize(
                 CompletionBundle.message("completion.class.name.hint.2", getActionShortcut(IdeActions.ACTION_CODE_COMPLETION)));
    }

    return null;
  }

  private static boolean shouldShowSecondSmartCompletionHint(final CompletionParameters parameters) {
    return parameters.getCompletionType() == CompletionType.CLASS_NAME &&
           parameters.getInvocationCount() == 1 &&
           parameters.getOriginalFile().getLanguage().isKindOf(JavaLanguage.INSTANCE);
  }
}

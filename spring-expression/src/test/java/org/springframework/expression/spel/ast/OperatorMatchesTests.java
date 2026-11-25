/*
 * Copyright 2002-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.expression.spel.ast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import org.springframework.expression.spel.ExpressionState;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelMessage;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests for SpEL's {@code matches} operator, in particular for the bounded
 * pattern cache shared by a parsed expression.
 *
 * @see OperatorMatches
 */
class OperatorMatchesTests {

	private static final int DISTINCT_REGEX_COUNT = 4 * OperatorMatches.MAX_PATTERN_CACHE_SIZE;


	@Test
	void maxPatternCacheSize() {
		assertThat(OperatorMatches.MAX_PATTERN_CACHE_SIZE).isEqualTo(256);
	}

	@Test
	void patternCacheIsBoundedForCachedExpressionWithDynamicRegex() throws Exception {
		// A single parsed (and typically cached) expression evaluated with
		// attacker-controlled regular expressions must not grow without limit.
		SpelExpression expression = (SpelExpression)
				new SpelExpressionParser().parseExpression("#input matches #regex");
		assertThat(expression.getAST()).isInstanceOf(OperatorMatches.class);
		StandardEvaluationContext context = new StandardEvaluationContext();

		for (int i = 0; i < DISTINCT_REGEX_COUNT; i++) {
			context.setVariable("input", "x" + i);
			context.setVariable("regex", "x" + i);
			assertThat(expression.getValue(context, Boolean.class)).isTrue();
			assertThat(patternCacheSize(expression.getAST())).isLessThanOrEqualTo(OperatorMatches.MAX_PATTERN_CACHE_SIZE);
		}
		assertThat(patternCacheSize(expression.getAST())).isEqualTo(OperatorMatches.MAX_PATTERN_CACHE_SIZE);

		// Evicted patterns are transparently recompiled on demand.
		context.setVariable("input", "x0");
		context.setVariable("regex", "x0");
		assertThat(expression.getValue(context, Boolean.class)).isTrue();
		context.setVariable("input", "y0");
		assertThat(expression.getValue(context, Boolean.class)).isFalse();
		assertThat(patternCacheSize(expression.getAST())).isLessThanOrEqualTo(OperatorMatches.MAX_PATTERN_CACHE_SIZE);
	}

	@Test
	void patternCacheIsSharedAndBoundedAcrossOperatorsInSameExpression() throws Exception {
		SpelExpression expression = (SpelExpression)
				new SpelExpressionParser().parseExpression("(#input matches #regex) and (('x' + #input) matches #regex2)");
		StandardEvaluationContext context = new StandardEvaluationContext();

		for (int i = 0; i < DISTINCT_REGEX_COUNT; i++) {
			context.setVariable("input", "a" + i);
			context.setVariable("regex", "a" + i);
			context.setVariable("regex2", "xa" + i);
			assertThat(expression.getValue(context, Boolean.class)).isTrue();
		}

		SpelNode and = expression.getAST();
		assertThat(patternCacheSize(and.getChild(0))).isLessThanOrEqualTo(OperatorMatches.MAX_PATTERN_CACHE_SIZE);
		assertThat(patternCacheSize(and.getChild(1))).isLessThanOrEqualTo(OperatorMatches.MAX_PATTERN_CACHE_SIZE);
		assertThat(patternCache(and.getChild(0))).isSameAs(patternCache(and.getChild(1)));
	}

	@Test
	void overlyLongRegexIsRejectedOnEveryEvaluationAndNeverCached() throws Exception {
		SpelExpression expression = (SpelExpression)
				new SpelExpressionParser().parseExpression("#input matches #regex");
		StandardEvaluationContext context = new StandardEvaluationContext();
		context.setVariable("input", "X");
		context.setVariable("regex", "X".repeat(1001));

		for (int i = 0; i < 3; i++) {
			assertThatExceptionOfType(SpelEvaluationException.class)
					.isThrownBy(() -> expression.getValue(context, Boolean.class))
					.satisfies(ex -> assertThat(ex.getMessageCode()).isEqualTo(SpelMessage.MAX_REGEX_LENGTH_EXCEEDED));
		}
		assertThat(patternCacheSize(expression.getAST())).isZero();

		context.setVariable("regex", "X".repeat(1000));
		assertThat(expression.getValue(context, Boolean.class)).isFalse();
		assertThat(patternCacheSize(expression.getAST())).isEqualTo(1);
	}

	@Test
	void invalidRegexIsReportedAndNotCached() throws Exception {
		SpelExpression expression = (SpelExpression)
				new SpelExpressionParser().parseExpression("#input matches #regex");
		StandardEvaluationContext context = new StandardEvaluationContext();
		context.setVariable("input", "abc");
		context.setVariable("regex", "(abc");

		assertThatExceptionOfType(SpelEvaluationException.class)
				.isThrownBy(() -> expression.getValue(context, Boolean.class))
				.satisfies(ex -> assertThat(ex.getMessageCode()).isEqualTo(SpelMessage.INVALID_PATTERN));
		assertThat(patternCacheSize(expression.getAST())).isZero();
	}

	@Test
	@SuppressWarnings("removal")
	void deprecatedConstructorUsesBoundedPatternCache() throws Exception {
		OperatorMatches matches = new OperatorMatches(-1, -1,
				new VariableReference("input", -1, -1), new VariableReference("regex", -1, -1));

		assertBoundedPatternCache(matches);
	}

	@Test
	@SuppressWarnings("removal")
	void deprecatedConstructorWithConcurrentMapIgnoresUnboundedMap() throws Exception {
		ConcurrentMap<String, Pattern> patternCacheMap = new ConcurrentHashMap<>();
		OperatorMatches matches = new OperatorMatches(patternCacheMap, -1, -1,
				new VariableReference("input", -1, -1), new VariableReference("regex", -1, -1));

		assertBoundedPatternCache(matches);
		assertThat(patternCacheMap).isEmpty();
	}


	private static void assertBoundedPatternCache(OperatorMatches matches) throws Exception {
		StandardEvaluationContext context = new StandardEvaluationContext();
		ExpressionState state = new ExpressionState(context);
		for (int i = 0; i < DISTINCT_REGEX_COUNT; i++) {
			context.setVariable("input", "z" + i);
			context.setVariable("regex", "z" + i);
			assertThat(matches.getValue(state)).isEqualTo(true);
		}
		assertThat(patternCacheSize(matches)).isEqualTo(OperatorMatches.MAX_PATTERN_CACHE_SIZE);
	}

	private static Object patternCache(SpelNode node) throws Exception {
		assertThat(node).isInstanceOf(OperatorMatches.class);
		Field field = OperatorMatches.class.getDeclaredField("patternCache");
		field.setAccessible(true);
		return field.get(node);
	}

	private static int patternCacheSize(SpelNode node) throws Exception {
		Object cache = patternCache(node);
		Method size = cache.getClass().getMethod("size");
		return (int) size.invoke(cache);
	}

}

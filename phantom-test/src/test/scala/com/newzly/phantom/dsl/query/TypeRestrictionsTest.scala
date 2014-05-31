/*
 * Copyright 2013 newzly ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.newzly.phantom.dsl.query

import org.scalatest.{ FlatSpec, Matchers, ParallelTestExecution }
import com.newzly.phantom.Implicits._
import com.newzly.phantom.tables.Primitives

class TypeRestrictionsTest extends FlatSpec with Matchers {
  it should "allow using a correct type for a value method" in {
    "Primitives.insert.value(_.boolean, true)" should compile
  }

  it should "not allow using a wrong type for a value method" in {
    "Primitives.insert.value(_.boolean, 5)" shouldNot compile
  }
}

/*******************************************************************************
 * Copyright (c) 2017 Pablo Pavon Marino and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the 2-clause BSD License 
 * which accompanies this distribution, and is available at
 * https://opensource.org/licenses/BSD-2-Clause
 *
 * Contributors:
 *     Pablo Pavon Marino and others - initial API and implementation
 *******************************************************************************/



 




package com.net2plan.internal;

import com.net2plan.utils.Triple;

import java.util.List;

/**
 * <p>Contract that must be fulfilled such that any external code (i.e. .class/.jar) can be run in {@code Net2Plan}.</p>
 *
 * @author Pablo Pavon-Marino, Jose-Luis Izquierdo-Zaragoza
 * @since 0.2.0
 */
public interface IExternal
{
	/**
	 * Returns the description.
	 *
	 * @return Description
	 * @since 0.2.0
	 */
	public String getDescription();

	/**
	 * <p>Returns the list of required parameters, where the first item of each element 
	 * is the parameter name, the second one is the parameter value, and the third 
	 * one is the parameter description.</p>
	 * 
	 * <p>It is possible to define type-specific parameters if the default value is set 
	 * according to the following rules (but user is responsible of checking in its own code):</p>
	 * 
	 * <ul>
	 * <li>If the default value is #select#	and a set of space- or comma-separated values, 
	 * the GUI will show a combobox with all the values, where the first one will be the 
	 * selected one by default. For example: "#select# hops km" will allow choosing in the 
	 * GUI between hops and km, where hops will be the default value.</li>
	 * <li>If the default value is #boolean# and true or false, the GUI will show a checkbox, 
	 * where the default value will be true or false, depending on the value accompanying to 
	 * #boolean#. For example: "#boolean# true" will show a checkbox marked by default.</li>
	 * <li>If the default value is #algorithm#, the GUI will prepare a new interface to 
	 * select an algorithm as parameter. Three new fields will be added, including "_file", 
	 * "_classname" and "_parameters" suffixes to the indicated parameter name, refer to 
	 * the {@code .class} or {@code .jar} file where the code is located, the class name, 
	 * and a set of parameters (pair of key-values separated by commas, where individual 
	 * key and value are separated with an equal symbol. The same applies to reports (#report#), 
	 * event generators (#eventGenerator#) and event processors (#eventProcessor#).</li>
	 * </ul>
	 *
	 * @return List of specific parameters
	 * @since 0.2.0
	 */
	public List<Triple<String, String, String>> getParameters();
}

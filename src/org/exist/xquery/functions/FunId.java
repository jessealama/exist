/*
 * eXist Open Source Native XML Database
 * Copyright (C) 2001-2006 The eXist team
 *  
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *  
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 *  
 *  $Id$
 */

package org.exist.xquery.functions;

import org.exist.dom.*;
import org.exist.storage.ElementValue;
import org.exist.util.XMLChar;
import org.exist.xquery.*;
import org.exist.xquery.value.*;
import org.w3c.dom.Node;

import java.util.Iterator;
import java.util.StringTokenizer;

public class FunId extends Function {

	public final static FunctionSignature signature[] = {
			new FunctionSignature(
				new QName("id", Function.BUILTIN_FUNCTION_NS),
				"Returns the sequence of element nodes that have an ID value " +
				"matching the value of one or more of the IDREF values supplied in $a. " +
				"If none is matching or $a is the empty sequence, returns the empty sequence.",
				new SequenceType[] {
					 new SequenceType(Type.STRING, Cardinality.ZERO_OR_MORE)},
				new SequenceType(Type.ELEMENT, Cardinality.ZERO_OR_MORE)),
            new FunctionSignature(
                    new QName("id", Function.BUILTIN_FUNCTION_NS),
                    "Returns the sequence of element nodes that have an ID value " +
                    "matching the value of one or more of the IDREF values supplied in $a. " +
                    "If none is matching or $a is the empty sequence, returns the empty sequence.",
                    new SequenceType[] {
                         new SequenceType(Type.STRING, Cardinality.ZERO_OR_MORE),
                         new SequenceType(Type.NODE, Cardinality.EXACTLY_ONE)},
                    new SequenceType(Type.ELEMENT, Cardinality.ZERO_OR_MORE))
    };
				
	/**
	 * Constructor for FunId.
	 */
	public FunId(XQueryContext context, FunctionSignature signature) {
		super(context, signature);
	}

	/**
	 * @see org.exist.xquery.Expression#eval(Sequence, Item)
	 */
	public Sequence eval(Sequence contextSequence, Item contextItem) throws XPathException {
        if (context.getProfiler().isEnabled()) {
            context.getProfiler().start(this);       
            context.getProfiler().message(this, Profiler.DEPENDENCIES, "DEPENDENCIES", Dependency.getDependenciesName(this.getDependencies()));
            if (contextSequence != null)
                context.getProfiler().message(this, Profiler.START_SEQUENCES, "CONTEXT SEQUENCE", contextSequence);
            if (contextItem != null)
                context.getProfiler().message(this, Profiler.START_SEQUENCES, "CONTEXT ITEM", contextItem.toSequence());
        }
        
        if (getArgumentCount() < 1)
			throw new XPathException("function id requires one argument");
		
        if(contextItem != null)
			contextSequence = contextItem.toSequence();
		
        Sequence result;
        Expression arg = getArgument(0);        
		Sequence idval = arg.eval(contextSequence);
		if(idval.isEmpty() || (getArgumentCount() == 1 && contextSequence != null && contextSequence.isEmpty()))
            result = Sequence.EMPTY_SEQUENCE;
        else {
    		result = new ExtArrayNodeSet();
    		String nextId;
    		DocumentSet docs;
            if (getArgumentCount() == 2) {
                // second argument should be a node, whose owner document will be
                // searched for the id
                Sequence nodes = getArgument(1).eval(contextSequence);
                if (nodes.isEmpty())
                    throw new XPathException(getASTNode(), 
                            "XPDY0002: no node or context item for fn:id");
                if (!Type.subTypeOf(nodes.itemAt(0).getType(), Type.NODE)) 
                	throw new XPathException(getASTNode(), 
                    "XPTY0004: fn:id() argument is not a node");               	
                NodeValue node = (NodeValue)nodes.itemAt(0);
                if (node.getImplementationType() == NodeValue.IN_MEMORY_NODE)
                    throw new XPathException(getASTNode(), "FODC0001: supplied node is not from a persistent document");
                MutableDocumentSet ndocs = new DefaultDocumentSet();
                ndocs.add(((NodeProxy)node).getDocument());
                docs = ndocs;
            } else if (contextSequence == null)
                throw new XPathException(getASTNode(), "XPDY0002: no context item specified");
            else if(!Type.subTypeOf(contextSequence.getItemType(), Type.NODE))
    			throw new XPathException(getASTNode(), "XPTY0004: context item is not a node");
    		else
    			docs = contextSequence.toNodeSet().getDocumentSet();
            
    		for(SequenceIterator i = idval.iterate(); i.hasNext(); ) {
    			nextId = i.nextItem().getStringValue();
                if (nextId.length() == 0)
                    continue;
    			if(nextId.indexOf(" ") != Constants.STRING_NOT_FOUND) {
    				// parse idrefs
    				StringTokenizer tok = new StringTokenizer(nextId, " ");
    				while(tok.hasMoreTokens()) {
    					nextId = tok.nextToken();
    					if(XMLChar.isValidNCName(nextId)) {
        					getId((NodeSet)result, docs, nextId);
                        }
    				}
    			} else {
    				if(XMLChar.isValidNCName(nextId)) {
        				getId((NodeSet)result, docs, nextId);
                    }
    			}
    		}
        }
        
        if (context.getProfiler().isEnabled()) 
            context.getProfiler().end(this, "", result); 
        
        return result;   
        
	}

	private void getId(NodeSet result, DocumentSet docs, String id) throws XPathException {
		NodeSet attribs = context.getBroker().getValueIndex().find(Constants.EQ, docs, null, -1, null, new StringValue(id, Type.ID));
		NodeProxy n, p;
		for (Iterator i = attribs.iterator(); i.hasNext();) {
			n = (NodeProxy) i.next();
			p = new NodeProxy(n.getDocument(), n.getNodeId().getParentId(), Node.ELEMENT_NODE);
			result.add(p);
		}
	}
}

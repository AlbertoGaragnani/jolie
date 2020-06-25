/***************************************************************************
 *   Copyright (C) 2006-2009 by Fabrizio Montesi <famontesi@gmail.com>     *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU Library General Public License as       *
 *   published by the Free Software Foundation; either version 2 of the    *
 *   License, or (at your option) any later version.                       *
 *                                                                         *
 *   This program is distributed in the hope that it will be useful,       *
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of        *
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
 *   GNU General Public License for more details.                          *
 *                                                                         *
 *   You should have received a copy of the GNU Library General Public     *
 *   License along with this program; if not, write to the                 *
 *   Free Software Foundation, Inc.,                                       *
 *   59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.             *
 *                                                                         *
 *   For details about the authors of this software, see the AUTHORS file. *
 ***************************************************************************/

package jolie.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import jolie.lang.Constants;
import jolie.net.CommChannel;
import jolie.process.TransformationReason;
import jolie.runtime.expression.Expression;
import jolie.runtime.typing.TypeCastingException;

class ValueLink extends Value implements Cloneable {
	private final VariablePath linkPath;

	private Value getLinkedValue() {
		return linkPath.getValue( this );
	}

	@Override
	public ValueVector getChildren( String childId ) {
		return getLinkedValue().getChildren( childId );
	}

	/*
	 * private void writeObject( ObjectOutputStream out ) throws IOException { out.writeObject(
	 * getLinkedValue() ); }
	 */

	@Override
	public final Value evaluate() {
		return getLinkedValue();
	}

	@Override
	public boolean hasChildren() {
		return getLinkedValue().hasChildren();
	}

	@Override
	public boolean hasChildren( String childId ) {
		return getLinkedValue().hasChildren( childId );
	}

	@Override
	protected void _refCopy( Value value ) {
		getLinkedValue()._refCopy( value );
	}

	@Override
	public void setValueObject( Object object ) {
		getLinkedValue().setValueObject( object );
	}

	@Override
	public void erase() {
		getLinkedValue().erase();
	}

	@Override
	public ValueLink clone() {
		return new ValueLink( linkPath );
	}

	@Override
	public void _deepCopy( Value value, boolean copyLinks ) {
		getLinkedValue()._deepCopy( value, copyLinks );
	}

	@Override
	public Map< String, ValueVector > children() {
		return getLinkedValue().children();
	}

	@Override
	public Object valueObject() {
		return getLinkedValue().valueObject();
	}

	public ValueLink( VariablePath path ) {
		assert (path != null);
		linkPath = path;
	}

	@Override
	public boolean isLink() {
		return true;
	}
}


class ValueImpl extends Value implements Cloneable, Serializable {
	private static final long serialVersionUID = 1L;

	private volatile Object valueObject = null;
	private final AtomicReference< Map< String, ValueVector > > children = new AtomicReference<>();

	@Override
	public void setValueObject( Object object ) {
		valueObject = object;
	}

	@Override
	public ValueVector getChildren( String childId ) {
		return children().computeIfAbsent( childId, k -> ValueVector.create() );
	}

	@Override
	public ValueImpl clone() {
		ValueImpl ret = new ValueImpl();
		ret._deepCopy( this, true );
		return ret;
	}

	@Override
	protected void _refCopy( Value value ) {
		setValueObject( value.valueObject() );
		this.children.set( value.children() );
	}

	@Override
	public final Value evaluate() {
		return this;
	}

	@Override
	public void erase() {
		valueObject = null;
		children.set( null );
	}

	protected ValueImpl() {}

	@Override
	public boolean isLink() {
		return false;
	}

	@Override
	public boolean hasChildren() {
		Map< String, ValueVector > c = children.get();
		return (c != null && !c.isEmpty());
	}

	@Override
	public boolean hasChildren( String childId ) {
		Map< String, ValueVector > c = children.get();
		return (c != null && c.containsKey( childId ));
	}

	@Override
	protected void _deepCopy( Value value, boolean copyLinks ) {
		/**
		 * TODO: check if a << b | b << a can generate deadlocks
		 */
		assignValue( value );

		if( value.hasChildren() ) {
			int i;
			ValueImpl newValue;
			Map< String, ValueVector > myChildren = children();
			for( Entry< String, ValueVector > entry : value.children().entrySet() ) {
				if( copyLinks && entry.getValue().isLink() ) {
					myChildren.put( entry.getKey(), ValueVector.createClone( entry.getValue() ) );
				} else {
					List< Value > otherVector = entry.getValue().valuesCopy();
					ValueVector vec = getChildren( entry.getKey(), myChildren );
					i = 0;
					for( Value v : otherVector ) {
						if( copyLinks && v.isLink() ) {
							vec.set( i, ((ValueLink) v).clone() );
						} else {
							newValue = (v.isUsedInCorrelation() ? new CSetValue() : new ValueImpl());
							newValue._deepCopy( v, copyLinks );
							vec.set( i, newValue );
						}
						i++;
					}
				}
			}
		}
	}

	private static ValueVector getChildren( String childId, Map< String, ValueVector > children ) {
		return children.computeIfAbsent( childId, k -> ValueVector.create() );
	}

	private final static int INITIAL_CAPACITY = 8;
	private final static float LOAD_FACTOR = 0.75f;

	@Override
	public Map< String, ValueVector > children() {
		// Create the map if not present
		children.getAndUpdate( v -> v == null ? new ConcurrentHashMap<>( INITIAL_CAPACITY, LOAD_FACTOR ) : v );
		return children.get();
	}

	@Override
	public Object valueObject() {
		return valueObject;
	}

	protected ValueImpl( Object object ) {
		valueObject = object;
	}

	public ValueImpl( Value val ) {
		valueObject = val.valueObject();
	}
}


/** TODO: remove code duplication from ValueImpl */
class RootValueImpl extends Value implements Cloneable {
	private final static int INITIAL_CAPACITY = 8;
	private final static float LOAD_FACTOR = 0.75f;

	private final Map< String, ValueVector > children =
		new ConcurrentHashMap<>( INITIAL_CAPACITY, LOAD_FACTOR );

	@Override
	public RootValueImpl clone() {
		RootValueImpl ret = new RootValueImpl();
		ret._deepCopy( this, true );
		return ret;
	}

	@Override
	public void setValueObject( Object object ) {}

	@Override
	protected void _refCopy( Value value ) {}

	@Override
	public ValueVector getChildren( String childId ) {
		return children.computeIfAbsent( childId, k -> ValueVector.create() );
	}

	@Override
	public final Value evaluate() {
		return this;
	}

	@Override
	public void erase() {
		children.clear();
	}

	@Override
	public boolean isLink() {
		return false;
	}

	@Override
	public final Map< String, ValueVector > children() {
		return children;
	}

	@Override
	public boolean hasChildren() {
		return children.isEmpty() == false;
	}

	@Override
	public boolean hasChildren( String childId ) {
		return children.containsKey( childId );
	}

	@Override
	protected void _deepCopy( Value value, boolean copyLinks ) {
		if( value.hasChildren() ) {
			int i;
			ValueImpl newValue;
			for( Entry< String, ValueVector > entry : value.children().entrySet() ) {
				if( copyLinks && entry.getValue().isLink() ) {
					children.put( entry.getKey(), ValueVector.createClone( entry.getValue() ) );
				} else {
					List< Value > otherVector = entry.getValue().valuesCopy();
					ValueVector vec = getChildren( entry.getKey(), children );
					i = 0;
					for( Value v : otherVector ) {
						if( copyLinks && v.isLink() ) {
							vec.set( i, ((ValueLink) v).clone() );
						} else {
							newValue = (v.isUsedInCorrelation() ? new CSetValue() : new ValueImpl());
							newValue._deepCopy( v, copyLinks );
							vec.set( i, newValue );
						}
						i++;
					}
				}
			}
		}
	}

	private static ValueVector getChildren( String childId, Map< String, ValueVector > children ) {
		return children.computeIfAbsent( childId, k -> ValueVector.create() );
	}

	@Override
	public Object valueObject() {
		return null;
	}
}


class CSetValue extends ValueImpl {
	private static final long serialVersionUID = Constants.serialVersionUID();

	// @Override
	// public void setValueObject( Object object ) {
	// // CommCore commCore = Interpreter.getInstance().commCore();
	// // synchronized( commCore.correlationLock() )
	// // removeFromRadixTree();
	// super.setValueObject( object );
	// // addToRadixTree();
	// // }
	// }

	@Override
	public CSetValue clone() {
		CSetValue ret = new CSetValue();
		ret._deepCopy( this, true );
		return ret;
	}

	@Override
	public boolean isUsedInCorrelation() {
		return true;
	}
}


/**
 * Handles JOLIE internal data representation.
 * 
 * @author Fabrizio Montesi 2007 - Claudio Guidi: added support for double values 2008 - Fabrizio
 *         Montesi: new system for internal value storing
 */
public abstract class Value implements Expression, Cloneable {
	public abstract boolean isLink();

	public static final Value UNDEFINED_VALUE = Value.create();

	public boolean isUsedInCorrelation() {
		return false;
	}

	public final static Value createRootValue() {
		return new RootValueImpl();
	}

	public final static Value createLink( VariablePath path ) {
		return new ValueLink( path );
	}

	public final static Value create() {
		return new ValueImpl();
	}

	public final static Value createCSetValue() {
		return new CSetValue();
	}

	public final static Value create( Boolean bool ) {
		return new ValueImpl( bool );
	}

	public final static Value create( String str ) {
		return new ValueImpl( str );
	}

	public final static Value create( Integer i ) {
		return new ValueImpl( i );
	}

	public final static Value create( Long l ) {
		return new ValueImpl( l );
	}

	public final static Value create( Double d ) {
		return new ValueImpl( d );
	}

	public final static Value create( ByteArray b ) {
		return new ValueImpl( b );
	}

	public final static Value create( Value value ) {
		return new ValueImpl( value );
	}

	public final static Value createClone( Value value ) {
		return value.clone();
	}

	public final static Value createDeepCopy( Value value ) {
		Value ret = Value.create();
		ret.deepCopy( value );
		return ret;
	}

	/**
	 * Makes this value an identical copy (by value) of the parameter, considering also its sub-tree. In
	 * case of a sub-link, its pointed Value tree is copied.
	 * 
	 * @param value The value to be copied.
	 */
	public final void deepCopy( Value value ) {
		_deepCopy( value, false );
	}

	public final void deepCopyWithLinks( Value value ) {
		_deepCopy( value, true );
	}



	public final void refCopy( Value value ) {
		_refCopy( value );
	}

	protected abstract void _refCopy( Value value );

	public abstract void erase();

	protected abstract void _deepCopy( Value value, boolean copyLinks );

	public abstract Map< String, ValueVector > children();

	public abstract Object valueObject();

	protected abstract void setValueObject( Object object );

	public abstract boolean hasChildren();

	public abstract boolean hasChildren( String childId );

	public abstract ValueVector getChildren( String childId );

	public final < V > V firstChildOrCompute( String childId, Function< ? super Value, ? extends V > mappingFunction,
		Function< ? super String, ? extends V > defaultMappingFunction ) {
		if( hasChildren( childId ) ) {
			return mappingFunction.apply( getFirstChild( childId ) );
		} else {
			return defaultMappingFunction.apply( childId );
		}
	}

	public final < V > V firstChildOrDefault( String childId, Function< ? super Value, ? extends V > mappingFunction,
		V defaultValue ) {
		if( hasChildren( childId ) ) {
			return mappingFunction.apply( getFirstChild( childId ) );
		} else {
			return defaultValue;
		}
	}

	@Override
	public abstract Value clone();

	public final Value getNewChild( String childId ) {
		final ValueVector vec = getChildren( childId );
		Value retVal = new ValueImpl();
		vec.add( retVal );

		return retVal;
	}

	public final Value getFirstChild( String childId ) {
		return getChildren( childId ).get( 0 );
	}

	public final void setFirstChild( String childId, Object object ) {
		getFirstChild( childId ).setValue( object );
	}

	@Override
	public abstract Value evaluate();

	public final void setValue( Object object ) {
		setValueObject( object );
	}

	public final void setValue( String object ) {
		setValueObject( object );
	}

	public final void setValue( Long object ) {
		setValueObject( object );
	}

	public final void setValue( Boolean object ) {
		setValueObject( object );
	}

	public final void setValue( Integer object ) {
		setValueObject( object );
	}

	public final void setValue( Double object ) {
		setValueObject( object );
	}

	public final synchronized boolean equals( Value val ) {
		boolean r = false;
		if( val.isDefined() ) {
			if( isByteArray() ) {
				r = byteArrayValue().equals( val.byteArrayValue() );
			} else if( isString() ) {
				r = strValue().equals( val.strValue() );
			} else if( isInt() ) {
				r = intValue() == val.intValue();
			} else if( isDouble() ) {
				r = doubleValue() == val.doubleValue();
			} else if( isBool() ) {
				r = boolValue() == val.boolValue();
			} else if( isLong() ) {
				r = longValue() == val.longValue();
			} else if( valueObject() != null ) {
				r = valueObject().equals( val.valueObject() );
			}
		} else {
			// undefined == undefined
			r = !isDefined();
		}
		return r;
	}

	public final boolean isInt() {
		return (valueObject() instanceof Integer);
	}

	public final boolean isLong() {
		return (valueObject() instanceof Long);
	}

	public final boolean isBool() {
		return (valueObject() instanceof Boolean);
	}

	public final boolean isByteArray() {
		return (valueObject() instanceof ByteArray);
	}

	public final boolean isDouble() {
		return (valueObject() instanceof Double);
	}

	public final boolean isString() {
		return (valueObject() instanceof String);
	}

	public final boolean isChannel() {
		return (valueObject() instanceof CommChannel);
	}

	public final boolean isDefined() {
		return (valueObject() != null);
	}

	public void setValue( CommChannel value ) {
		setValueObject( value );
	}

	public CommChannel channelValue() {
		Object o = valueObject();
		if( o instanceof CommChannel == false ) {
			return null;
		}
		return (CommChannel) o;
	}

	public String strValue() {
		try {
			return strValueStrict();
		} catch( TypeCastingException e ) {
			return "";
		}
	}

	public final String strValueStrict()
		throws TypeCastingException {
		Object o = valueObject();
		if( o == null ) {
			throw new TypeCastingException();
		} else if( o instanceof String ) {
			return (String) o;
		}
		return o.toString();
	}

	public ByteArray byteArrayValue() {
		try {
			return byteArrayValueStrict();
		} catch( TypeCastingException e ) {
			return new ByteArray( new byte[ 0 ] );
		}
	}

	public ByteArray byteArrayValueStrict()
		throws TypeCastingException {
		ByteArray r = null;
		Object o = valueObject();
		if( o == null ) {
			throw new TypeCastingException();
		} else if( o instanceof ByteArray ) {
			r = (ByteArray) o;
		} else if( o instanceof Integer ) {
			// TODO: This is slow
			ByteArrayOutputStream bbstream = new ByteArrayOutputStream( 4 );
			try {
				new DataOutputStream( bbstream ).writeInt( (Integer) o );
				r = new ByteArray( bbstream.toByteArray() );
			} catch( IOException e ) {
				throw new TypeCastingException();
			}
		} else if( o instanceof Long ) {
			// TODO: This is slow
			ByteArrayOutputStream bbstream = new ByteArrayOutputStream( 8 );
			try {
				new DataOutputStream( bbstream ).writeLong( (Long) o );
				r = new ByteArray( bbstream.toByteArray() );
			} catch( IOException e ) {
				throw new TypeCastingException();
			}
		} else if( o instanceof Boolean ) {
			// TODO: This is slow
			ByteArrayOutputStream bbstream = new ByteArrayOutputStream( 1 );
			try {
				new DataOutputStream( bbstream ).writeBoolean( (Boolean) o );
				r = new ByteArray( bbstream.toByteArray() );
			} catch( IOException e ) {
				throw new TypeCastingException();
			}
		} else if( o instanceof String ) {
			r = new ByteArray( ((String) o).getBytes() );
		} else if( o instanceof Double ) {
			// TODO: This is slow
			ByteArrayOutputStream bbstream = new ByteArrayOutputStream( 8 );
			try {
				new DataOutputStream( bbstream ).writeDouble( (Double) o );
				r = new ByteArray( bbstream.toByteArray() );
			} catch( IOException e ) {
				throw new TypeCastingException();
			}
		}
		return r;
	}

	public int intValue() {
		try {
			return intValueStrict();
		} catch( TypeCastingException e ) {
			return 0;
		}
	}

	public final int intValueStrict()
		throws TypeCastingException {
		int r = 0;
		Object o = valueObject();
		if( o == null ) {
			throw new TypeCastingException();
		} else if( o instanceof Integer ) {
			r = ((Integer) o).intValue();
		} else if( o instanceof Double ) {
			r = ((Double) o).intValue();
		} else if( o instanceof Long ) {
			r = ((Long) o).intValue();
		} else if( o instanceof Boolean ) {
			r = (((Boolean) o).booleanValue() == true) ? 1 : 0;
		} else if( o instanceof String ) {
			try {
				r = Integer.parseInt( ((String) o).trim() );
			} catch( NumberFormatException nfe ) {
				throw new TypeCastingException();
			}
		} else if( o instanceof ByteArray ) {
			try {
				return new DataInputStream( new ByteArrayInputStream( ((ByteArray) o).getBytes() ) ).readInt();
			} catch( IOException e ) {
				throw new TypeCastingException();
			}
		}
		return r;
	}

	public boolean boolValue() {
		try {
			return boolValueStrict();
		} catch( TypeCastingException e ) {
			return false;
		}
	}

	public boolean boolValueStrict()
		throws TypeCastingException {
		boolean r = false;
		Object o = valueObject();
		if( o == null ) {
			throw new TypeCastingException();
		} else if( o instanceof Boolean ) {
			r = ((Boolean) o).booleanValue();
		} else if( o instanceof Number ) {
			if( ((Number) o).longValue() > 0 ) {
				r = true;
			}
		} else if( o instanceof String ) {
			r = Boolean.parseBoolean( ((String) o).trim() );
		} else if( o instanceof ByteArray ) {
			try {
				return new DataInputStream( new ByteArrayInputStream( ((ByteArray) o).getBytes() ) ).readBoolean();
			} catch( IOException e ) {
				throw new TypeCastingException();
			}
		}

		return r;
	}

	public long longValue() {
		try {
			return longValueStrict();
		} catch( TypeCastingException e ) {
			return 0L;
		}
	}

	public final long longValueStrict()
		throws TypeCastingException {
		long r = 0L;
		Object o = valueObject();
		if( o == null ) {
			throw new TypeCastingException();
		} else if( o instanceof Long ) {
			r = ((Long) o).longValue();
		} else if( o instanceof Integer ) {
			r = ((Integer) o).longValue(); // added by Balint Maschio
		} else if( o instanceof Boolean ) {
			r = (((Boolean) o).booleanValue() == true) ? 1L : 0L;
		} else if( o instanceof Double ) {
			r = ((Double) o).longValue();
		} else if( o instanceof String ) {
			try {
				r = Long.parseLong( ((String) o).trim() );
			} catch( NumberFormatException nfe ) {
				throw new TypeCastingException();
			}
		} else if( o instanceof ByteArray ) {
			try {
				return new DataInputStream( new ByteArrayInputStream( ((ByteArray) o).getBytes() ) ).readLong();
			} catch( IOException e ) {
				throw new TypeCastingException();
			}
		}
		return r;
	}

	public double doubleValue() {
		try {
			return doubleValueStrict();
		} catch( TypeCastingException e ) {
			return 0.0;
		}
	}

	public final double doubleValueStrict()
		throws TypeCastingException {
		double r = 0.0;
		Object o = valueObject();
		if( o == null ) {
			throw new TypeCastingException();
		} else if( o instanceof Integer ) {
			r = ((Integer) o).doubleValue();
		} else if( o instanceof Double ) {
			r = ((Double) o).doubleValue();
		} else if( o instanceof Long ) {
			r = ((Long) o).doubleValue();
		} else if( o instanceof Boolean ) {
			r = (((Boolean) o).booleanValue() == true) ? 1.0 : 0.0;
		} else if( o instanceof String ) {
			try {
				r = Double.parseDouble( ((String) o).trim() );
			} catch( NumberFormatException nfe ) {
				throw new TypeCastingException();
			}
		} else if( o instanceof ByteArray ) {
			try {
				return new DataInputStream( new ByteArrayInputStream( ((ByteArray) o).getBytes() ) ).readDouble();
			} catch( IOException e ) {
				throw new TypeCastingException();
			}
		}
		return r;
	}

	public final synchronized void add( Value val ) {
		if( isDefined() ) {
			if( isString() || val.isString() ) {
				setValue( strValue() + val.strValue() );
			} else if( isDouble() || val.isDouble() ) {
				setValue( doubleValue() + val.doubleValue() );
			} else if( isLong() || val.isLong() ) {
				setValue( longValue() + val.longValue() );
			} else if( isInt() || val.isInt() ) {
				setValue( intValue() + val.intValue() );
			} else if( isBool() || val.isBool() ) {
				setValue( boolValue() || val.boolValue() );
			} else {
				setValue( strValue() + val.strValue() );
			}
		} else {
			assignValue( val );
		}
	}

	public final synchronized void subtract( Value val ) {
		if( !isDefined() ) {
			if( val.isDouble() ) {
				setValue( -val.doubleValue() );
			} else if( val.isLong() ) {
				setValue( -val.longValue() );
			} else if( val.isInt() ) {
				setValue( -val.intValue() );
			} else if( val.isBool() ) {
				setValue( !val.boolValue() );
			} else {
				assignValue( val );
			}
		} else if( isDouble() || val.isDouble() ) {
			setValue( doubleValue() - val.doubleValue() );
		} else if( isLong() || val.isLong() ) {
			setValue( longValue() - val.longValue() );
		} else if( isInt() || val.isInt() ) {
			setValue( intValue() - val.intValue() );
		}
	}

	public synchronized final void multiply( Value val ) {
		if( isDefined() ) {
			if( isDouble() || val.isDouble() ) {
				setValue( doubleValue() * val.doubleValue() );
			} else if( isLong() || val.isLong() ) {
				setValue( longValue() * val.longValue() );
			} else if( isInt() || val.isInt() ) {
				setValue( intValue() * val.intValue() );
			} else if( isBool() || val.isBool() ) {
				setValue( boolValue() && val.boolValue() );
			}
		} else {
			assignValue( val );
		}
	}

	public synchronized final void divide( Value val ) {
		if( !isDefined() ) {
			setValue( 0 );
		} else if( isDouble() || val.isDouble() ) {
			setValue( doubleValue() / val.doubleValue() );
		} else if( isLong() || val.isLong() ) {
			setValue( longValue() / val.longValue() );
		} else if( isInt() || val.isInt() ) {
			setValue( intValue() / val.intValue() );
		}
	}

	public synchronized final void modulo( Value val ) {
		if( !isDefined() ) {
			assignValue( val );
		} else if( isDouble() || val.isDouble() ) {
			setValue( doubleValue() % val.doubleValue() );
		} else if( isLong() || val.isLong() ) {
			setValue( longValue() % val.longValue() );
		} else if( isInt() || val.isInt() ) {
			setValue( intValue() % val.intValue() );
		}
	}

	public final void assignValue( Value val ) {
		setValueObject( val.valueObject() );
	}

	@Override
	public Expression cloneExpression( TransformationReason reason ) {
		return Value.createClone( this );
	}
}

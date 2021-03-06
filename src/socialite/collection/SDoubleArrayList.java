package socialite.collection;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Arrays;

import gnu.trove.list.array.TDoubleArrayList;

public class SDoubleArrayList extends TDoubleArrayList {
	static final long serialVersionUID = 1L;
	
	public SDoubleArrayList() {
		// should be only called by the serialization code
		super(0);
	}

	public SDoubleArrayList(int capacity) {
		super(capacity);
	}

	public int capacity() { return _data.length; }
	public boolean filledToCapacity() {
		if (_pos == _data.length)
			return true;
		return false;
	}
	
	public void addAllFast(SDoubleArrayList other) {
		if (other==null) return;
		int remain = _data.length - _pos; 
			
		if (remain < other.size()) {
			int newCapacity=Math.max((int)(_data.length*1.5f), _pos+other.size()+1);			
			_data = Arrays.copyOf(_data, newCapacity);
		}
		
		System.arraycopy(other._data, 0, _data, _pos, other.size());
		_pos += other.size();
	}
	
	public void ensureCapacity(int capacity) {
		if (capacity > _data.length) {
			int newCap;
			/*if (_data.length < 128) {
				newCap = Math.max((int) (_data.length * 2), capacity);
			} else*/ if (_data.length < 16*1024) {
				newCap = Math.max((int) (_data.length * 1.75f), capacity);
			} else {
				newCap = Math.max((int) (_data.length * 1.5f), capacity);
			}
			
			//if (newCap < capacity) newCap = capacity;
			double[] tmp = new double[newCap];
			System.arraycopy(_data, 0, tmp, 0, _data.length);
			_data = tmp;
		}
	}

	public int binarySearch(double value) {
		if (_pos==0) return -1;
		else if (value > _data[_pos-1]) return -(_pos+1);
		
		if (_pos > 8)
			return super.binarySearch(value);

		for (int i = 0; i < _pos; i++) {
			double v = _data[i];
			if (v == value)
				return i;
			if (v > value)
				return -(i + 1);
		}
		return -(_pos + 1);
	}

	public void writeExternal(ObjectOutput out) throws IOException {
		// VERSION
		out.writeByte(0);
		// POSITION
		out.writeInt(_pos);
		// NO_ENTRY_VALUE
		out.writeDouble(no_entry_value);

		// ENTRIES
		int len = _pos;
		out.writeInt(len);
		for (int i = 0; i < len; i++) {
			out.writeDouble(_data[i]);
		}
	}

	public void readExternal(ObjectInput in) throws IOException,
			ClassNotFoundException {
		// VERSION
		in.readByte();
		// POSITION
		_pos = in.readInt();
		// NO_ENTRY_VALUE
		no_entry_value = in.readDouble();

		// ENTRIES
		int len = in.readInt();
		if (_data==null) _data = new double[len];
		if (_data.length < len) _data = new double[len];
		for (int i = 0; i < len; i++) {
			_data[i] = in.readDouble();
		}
	}

}

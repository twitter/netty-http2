package com.twitter.http2;

import io.netty.handler.codec.DefaultHeaders.NameValidator;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.util.AsciiString;
import io.netty.util.ByteProcessor;
import io.netty.util.internal.PlatformDependent;

import static io.netty.util.internal.ObjectUtil.checkNotNull;

public class DefaultHttp2Headers extends DefaultHttpHeaders {
  private static final ByteProcessor HEADER_NAME_VALIDATOR = new ByteProcessor() {
    @Override
    public boolean process(byte value) throws Exception {
      validateChar((char) (value & 0xFF));
      return true;
    }
  };

  private static void validateChar(char character) {
    switch (character) {
      case '\t':
      case '\n':
      case 0x0b:
      case '\f':
      case '\r':
      case ' ':
      case ',':
      case ';':
      case '=':
        throw new IllegalArgumentException(
                "a header name cannot contain the following prohibited characters: =,; \\t\\r\\n\\v\\f: " +
                        character);
      default:
        // Check to see if the character is not an ASCII character, or invalid
        if (character > 127) {
          throw new IllegalArgumentException("a header name cannot contain non-ASCII character: " +
                  character);
        }
    }
  }

  static final NameValidator<CharSequence> Http2NameValidator = new NameValidator<CharSequence>() {
    @Override
    public void validateName(CharSequence name) {
      if (name instanceof AsciiString) {
        try {
          ((AsciiString) name).forEachByte(HEADER_NAME_VALIDATOR);
        } catch (Exception e) {
          PlatformDependent.throwException(e);
        }
      } else {
        checkNotNull(name, "name");
        // Go through each character in the name
        for (int index = 0; index < name.length(); ++index) {
          validateChar(name.charAt(index));
        }
      }
    }
  };

  public DefaultHttp2Headers() {
    super(true, Http2NameValidator);
  }
}

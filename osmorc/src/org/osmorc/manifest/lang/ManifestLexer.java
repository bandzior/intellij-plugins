/*
 * Copyright (c) 2007-2009, Osmorc Development Team
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright notice, this list
 *       of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright notice, this
 *       list of conditions and the following disclaimer in the documentation and/or other
 *       materials provided with the distribution.
 *     * Neither the name of 'Osmorc Development Team' nor the names of its contributors may be
 *       used to endorse or promote products derived from this software without specific
 *       prior written permission.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT
 * OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR
 * TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.osmorc.manifest.lang;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Robert F. Beeger (robert@beeger.net)
 */
public class ManifestLexer extends LexerBase {

  private CharSequence myBuffer;
  private int myEndOffset;
  private int myTokenStart;
  private int myTokenEnd;
  private int myCurrentState;
  private IElementType myTokenType;

  private static final int INITIAL_STATE = 0;
  private static final int WAITING_FOR_HEADER_ASSIGNMENT_STATE = 1;
  private static final int WAITING_FOR_HEADER_ASSIGNMENT_AFTER_BAD_CHARACTER_STATE = 2;
  private static final int WAITING_FOR_SPACE_AFTER_HEADER_NAME_STATE = 3;

  private static final Map<Character, IElementType> SPECIAL_CHARACTERS_TOKEN_MAPPING;

  static {
    SPECIAL_CHARACTERS_TOKEN_MAPPING = new HashMap<Character, IElementType>();
    SPECIAL_CHARACTERS_TOKEN_MAPPING.put(':', ManifestTokenType.COLON);
    SPECIAL_CHARACTERS_TOKEN_MAPPING.put(';', ManifestTokenType.SEMICOLON);
    SPECIAL_CHARACTERS_TOKEN_MAPPING.put(',', ManifestTokenType.COMMA);
    SPECIAL_CHARACTERS_TOKEN_MAPPING.put('=', ManifestTokenType.EQUALS);
    SPECIAL_CHARACTERS_TOKEN_MAPPING.put('(', ManifestTokenType.OPENING_PARENTHESIS_TOKEN);
    SPECIAL_CHARACTERS_TOKEN_MAPPING.put(')', ManifestTokenType.CLOSING_PARENTHESIS_TOKEN);
    SPECIAL_CHARACTERS_TOKEN_MAPPING.put('[', ManifestTokenType.OPENING_BRACKET_TOKEN);
    SPECIAL_CHARACTERS_TOKEN_MAPPING.put(']', ManifestTokenType.CLOSING_BRACKET_TOKEN);
    SPECIAL_CHARACTERS_TOKEN_MAPPING.put('\"', ManifestTokenType.QUOTE);
  }

  public ManifestLexer() {
  }

  public void start(CharSequence buffer, int startOffset, int endOffset, int initialState) {
    this.myBuffer = buffer;
    this.myEndOffset = endOffset;
    myCurrentState = initialState;

    myTokenStart = startOffset;
    parseNextToken();
  }

  public void advance() {
    myTokenStart = myTokenEnd;
    parseNextToken();
  }

  public int getState() {
    return myCurrentState;
  }

  @Nullable
  public IElementType getTokenType() {
    return myTokenType;
  }

  public int getTokenStart() {
    return myTokenStart;
  }

  public int getTokenEnd() {
    return myTokenEnd;
  }

  public int getBufferEnd() {
    return myEndOffset;
  }

  public CharSequence getBufferSequence() {
    return myBuffer;
  }

  private void parseNextToken() {
    if (myTokenStart < myEndOffset) {
      if (isNewline(myTokenStart)) {
        myTokenType = isLineStart(myTokenStart) ? ManifestTokenType.SECTION_END : ManifestTokenType.NEWLINE;
        myTokenEnd = myTokenStart + 1;
        myCurrentState = INITIAL_STATE;
      }
      else if (myCurrentState == WAITING_FOR_HEADER_ASSIGNMENT_STATE ||
               myCurrentState == WAITING_FOR_HEADER_ASSIGNMENT_AFTER_BAD_CHARACTER_STATE) {
        if (isColon(myTokenStart)) {
          myTokenType = ManifestTokenType.COLON;
          myCurrentState = WAITING_FOR_SPACE_AFTER_HEADER_NAME_STATE;
        }
        else {
          myTokenType = TokenType.BAD_CHARACTER;
          myCurrentState = WAITING_FOR_HEADER_ASSIGNMENT_AFTER_BAD_CHARACTER_STATE;
        }
        myTokenEnd = myTokenStart + 1;
      }
      else if (myCurrentState == WAITING_FOR_SPACE_AFTER_HEADER_NAME_STATE) {
        if (isSpace(myTokenStart)) {
          myTokenType = ManifestTokenType.SIGNIFICANT_SPACE;
        }
        else {
          myTokenType = TokenType.BAD_CHARACTER;
        }
        myCurrentState = INITIAL_STATE;
        myTokenEnd = myTokenStart + 1;
      }
      else if (isHeaderStart(myTokenStart)) {
        if (isAlphaNum(myTokenStart)) {
          myTokenEnd = myTokenStart + 1;
          while (myTokenEnd < myEndOffset && isHeaderChar(myTokenEnd)) {
            myTokenEnd++;
          }
        }
        myTokenType = ManifestTokenType.HEADER_NAME;
        myCurrentState = WAITING_FOR_HEADER_ASSIGNMENT_STATE;
      }
      else if (isContinuationStart(myTokenStart)) {
        myTokenType = ManifestTokenType.SIGNIFICANT_SPACE;
        myTokenEnd = myTokenStart + 1;
        myCurrentState = INITIAL_STATE;
      }
      else if (isSpecialCharacter(myTokenStart)) {
        myTokenType = getTokenTypeForSpecialCharacter(myTokenStart);
        myTokenEnd = myTokenStart + 1;
        myCurrentState = INITIAL_STATE;
      }
      else {
        myTokenEnd = myTokenStart;
        while (myTokenEnd < myEndOffset && !isSpecialCharacter(myTokenEnd) && !isNewline(myTokenEnd)) {
          myTokenEnd++;
        }
        myTokenType = ManifestTokenType.HEADER_VALUE_PART;
      }
    }
    else {
      myTokenType = null;
      myTokenEnd = myTokenStart;
    }
  }

  private boolean isNewline(int position) {
    return '\n' == myBuffer.charAt(position);
  }

  private boolean isHeaderStart(int position) {
    return isLineStart(position) && !Character.isWhitespace(myBuffer.charAt(position));
  }

  private boolean isAlphaNum(int position) {
    return Character.isLetterOrDigit(myBuffer.charAt(position));
  }

  private boolean isHeaderChar(int position) {
    return isAlphaNum(position) || myBuffer.charAt(position) == '-' || myBuffer.charAt(position) == '_';
  }

  private boolean isContinuationStart(int position) {
    return isLineStart(position) && !isHeaderStart(position);
  }

  private boolean isLineStart(int position) {
    return (position == 0 || isNewline(position - 1));
  }

  private boolean isSpace(int position) {
    return myBuffer.charAt(position) == ' ';
  }

  private boolean isColon(int position) {
    return myBuffer.charAt(position) == ':';
  }

  private boolean isSpecialCharacter(int position) {
    return SPECIAL_CHARACTERS_TOKEN_MAPPING.get(myBuffer.charAt(position)) != null;
  }

  private IElementType getTokenTypeForSpecialCharacter(int position) {
    return SPECIAL_CHARACTERS_TOKEN_MAPPING.get(myBuffer.charAt(position));
  }
}

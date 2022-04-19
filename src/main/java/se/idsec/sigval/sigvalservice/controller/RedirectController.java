/*
 * Copyright (c) 2022. IDsec Solutions AB
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

package se.idsec.sigval.sigvalservice.controller;

import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import se.swedenconnect.sigval.commons.document.DocType;

import javax.servlet.http.HttpSession;

@Controller
@NoArgsConstructor
public class RedirectController {

  //private final HttpSession httpSession;


/*
  @RequestMapping("/issue-svt-legacy")
  public String redirectToSvtIssuingService(){

    DocType docType = (DocType) httpSession.getAttribute(SessionAttr.docType.name());
    if  (docType == null) return "redirect:/";
    switch (docType) {

    case XML:
      return "redirect:/xmlsvt";
    case PDF:
      return "redirect:/pdfsvt";
    case JOSE:
    case JOSE_COMPACT:
      return "redirect:/josesvt";
    }
    return "redirect:/";
  }
*/

  @RequestMapping("/home")
  public String langSelect(){
    return "redirect:/";
  }

}

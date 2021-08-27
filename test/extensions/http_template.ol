from ..test-unit import TestUnitInterface
from  .private.http_template_interface import HttpTemplateInterface
from  .private.http_template_server import HttpTemplateServer


service Test {


	inputPort TestUnitInput {
		location: "local"
		interfaces: TestUnitInterface
	}


    outputPort TestHttpTemplate {
        interfaces: HttpTemplateInterface
        protocol: "http"{
           osc.getOrder.template="/api/orders/{id}" 
           osc.getOrder.method="GET"
           osc.getOrder.outHeaders.("Authorization")= "token"
           osc.getOrders.template="/api/orders" 
           osc.getOrders.method="GET"
           osc.getOrders.outHeaders.("Authorization")= "token"
           osc.addOrder.template="/api/orders" 
           osc.addOrder.method="POST"
           osc.addOrder.outHeaders.("Authorization")= "token"
           osc.addOrder.statusCodes.IOException = 500
        }
        Location : "socket://localhost:9099"
    }


   embed HttpTemplateServer in TestHttpTemplate
    

	main {
		test()() {
			/*
			* Write the code of your test here (replace nullProcess),
			* and replace the first line of the copyright header with your data.
			*
			* The test is supposed to throw a TestFailed fault in case of a failure.
			* You should add a description that reports what the failure was about,
			* for example:
			*
			* throw( TestFailed, "string concatenation does not match correct result" )
			*/
			addOrder@TestHttpTemplate({token="sometoken" 
                                       ammount = 10.0})()
            addOrder@TestHttpTemplate({token="sometoken" 
                                       ammount = 11.0})()
            addOrder@TestHttpTemplate({token="sometoken" 
                                       ammount = 21.0})()    
            getOrders@TestHttpTemplate({token="sometoken" 
                                       ammount = 21.0})(resultGetOrders) 
            if (#resultGetOrders.orders!=3){
                throw( TestFailed, "wrong number of results in getOrders" )
            }    

            getOrder@TestHttpTemplate({token="sometoken" 
                                       id = resultGetOrders.orders.id})(resultGetOrder) 
            if(resultGetOrder.ammount != resultGetOrders.orders.id){
                throw( TestFailed, "wrong number of results in getOrders" )
            }                                                                                                         

		}
	}
}
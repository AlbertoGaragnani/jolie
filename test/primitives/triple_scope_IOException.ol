include "exec.iol"
include "string_utils.iol"
include "console.iol"

include "../AbstractTestUnit.iol"

define doTest {	
	req = "jolie"
	;
	with(req) {
		.args[0] = "triple_scope_IOException2.ol"; 
		.workingDirectory="./primitives/unhandled_faults/";
		.stdOutConsoleEnable = true;
		.waitFor = 1
	}
	;
	exec@Exec(req)(res)
	;
	valueToPrettyString@StringUtils(res)(s);
	
	undef(req);
	req = s;
	req.substring="Thrown unhandled fault: IOException";
	contains@StringUtils(req)(contain);
	if (!contain){
		throw( TestFailed, "Not an IOException raised" )
	}

	req.substring="Connection refused";
	contains@StringUtils(req)(contain);
	if (!contain){
		throw( TestFailed, "IOException: not a 'Connection refused' error" )
	}
}